from __future__ import annotations

import csv
import io
from datetime import date, datetime
from typing import Any
from urllib.parse import urlparse


def _rows(value: Any) -> list[dict[str, str]]:
    if isinstance(value, list):
        return [{str(key): "" if item is None else str(item) for key, item in row.items()} for row in value if isinstance(row, dict)]
    if isinstance(value, str) and value.strip():
        return [dict(row) for row in csv.DictReader(io.StringIO(value))]
    return []


def data_csv_compare(csvA: Any, csvB: Any, keyColumns: list[str] | None = None, **_: Any):
    before = _rows(csvA)
    after = _rows(csvB)
    if not before and not after:
        raise ValueError("CSV inputs are empty or invalid")
    columns = keyColumns or ([next(iter(before[0]))] if before else [next(iter(after[0]))])
    if not columns or any(any(key not in row for key in columns) for row in before + after):
        raise ValueError("CSV key columns are missing")
    key = lambda row: tuple(row[name] for name in columns)
    left = {key(row): row for row in before}
    right = {key(row): row for row in after}
    changes = []
    for item_key in sorted(left.keys() | right.keys()):
        if item_key not in left:
            changes.append({"changeType": "ADDED", "key": list(item_key), "after": right[item_key]})
        elif item_key not in right:
            changes.append({"changeType": "REMOVED", "key": list(item_key), "before": left[item_key]})
        elif left[item_key] != right[item_key]:
            changes.append({"changeType": "MODIFIED", "key": list(item_key), "before": left[item_key], "after": right[item_key]})
    return {"changedRows": changes}


def template_markdown_table(changedRows: list[dict[str, Any]], **context: Any):
    lines = ["| changeType | key |", "|---|---|"]
    lines.extend(f"| {row.get('changeType', '')} | {', '.join(row.get('key', []))} |" for row in changedRows)
    result = {"changedRows": changedRows, "rendered": "\n".join(lines)}
    if context.get("summary") is not None:
        result["summary"] = context["summary"]
    return result


def _contract_result(values: dict[str, Any], contract: list[dict[str, Any]] | None) -> dict[str, Any]:
    if not contract:
        return values
    declared = [str(field.get("name")) for field in contract if field.get("name")]
    return {name: values[name] for name in declared if name in values}


def _matches_contract(value: Any, field: dict[str, Any]) -> bool:
    expected = field.get("type")
    expected_types = {
        "string": str,
        "array": list,
        "object": dict,
        "boolean": bool,
        "number": (int, float),
        "integer": int,
    }
    if expected in expected_types:
        if not isinstance(value, expected_types[expected]):
            return False
        if expected in {"number", "integer"} and isinstance(value, bool):
            return False
    if expected == "string":
        if field.get("minLength") is not None and len(value) < int(field["minLength"]):
            return False
        if field.get("enumValues") and value not in field["enumValues"]:
            return False
        if field.get("format") == "uri" and not all((urlparse(value).scheme, urlparse(value).netloc)):
            return False
        try:
            if field.get("format") == "date":
                date.fromisoformat(value)
            elif field.get("format") == "date-time":
                datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            return False
    if expected in {"number", "integer"}:
        if field.get("minimum") is not None and value < field["minimum"]:
            return False
        if field.get("maximum") is not None and value > field["maximum"]:
            return False
    if expected == "object" and field.get("objectSchema"):
        nested_contract = field["objectSchema"]
        declared = {str(nested.get("name")) for nested in nested_contract}
        if any(key not in declared for key in value):
            return False
        for nested in nested_contract:
            name = str(nested.get("name"))
            if nested.get("required") and name not in value:
                return False
            if name in value and not _matches_contract(value[name], nested):
                return False
    if expected != "array":
        return True
    minimum = field.get("minItems")
    maximum = field.get("maxItems")
    if minimum is not None and len(value) < int(minimum):
        return False
    if maximum is not None and len(value) > int(maximum):
        return False
    if field.get("uniqueItems") and len({repr(item) for item in value}) != len(value):
        return False
    unique_by = field.get("uniqueBy")
    if unique_by:
        keys = [item.get(unique_by) for item in value if isinstance(item, dict) and unique_by in item]
        if len(keys) != len(value) or len({repr(key) for key in keys}) != len(keys):
            return False
    item_type = field.get("itemType")
    item_schema = field.get("itemSchema")
    for item in value:
        if item_type and not _matches_contract(item, {"type": item_type}):
            return False
        if item_type == "string" and not _matches_contract(item, {
            "type": "string", "format": field.get("itemFormat"), "minLength": field.get("itemMinLength"),
        }):
            return False
        if item_type == "object" and item_schema:
            declared = {str(nested.get("name")) for nested in item_schema}
            if any(key not in declared for key in item):
                return False
            for nested in item_schema:
                name = str(nested.get("name"))
                if nested.get("required") and name not in item:
                    return False
                if name in item and not _matches_contract(item[name], nested):
                    return False
    return True


def _context_matches_contract(context: dict[str, Any], contract: list[dict[str, Any]] | None) -> bool:
    if not contract:
        return True
    for field in contract:
        name = str(field.get("name"))
        if field.get("required") and name not in context:
            return False
        if name in context and not _matches_contract(context[name], field):
            return False
    return True


def quality_check(
    agentownInputContract: list[dict[str, Any]] | None = None,
    agentownOutputContract: list[dict[str, Any]] | None = None,
    agentownResultFields: list[str] | None = None,
    agentownFailClosed: bool = False,
    **context: Any,
):
    missing = context.get("missingLocations") or context.get("missingFields") or []
    failures = context.get("failures") or context.get("errors") or []
    status = str(context.get("reportStatus") or context.get("status") or "").upper()
    result_fields = agentownResultFields or ["results"]
    has_parallel_results = any(isinstance(context.get(name), list) and bool(context[name]) for name in result_fields)
    has_tool_result = any(name in context for name in ("changedRows", "rendered", "renderedResponse"))
    contract_passed = _context_matches_contract(context, agentownInputContract)
    positive_signal = bool(agentownInputContract) or status in {"READY", "SUCCEEDED", "COMPLETED"} \
        or has_parallel_results or has_tool_result
    passed = contract_passed and not missing and not failures and positive_signal
    if not passed and agentownFailClosed:
        raise ValueError("Quality contract validation failed")
    return _contract_result({**context, "qualityPassed": passed}, agentownOutputContract)


def template_plain_text(
    content: Any = None,
    report: Any = None,
    response: Any = None,
    agentownOutputContract: list[dict[str, Any]] | None = None,
    **context: Any,
):
    rendered = content if content is not None else report if report is not None else response
    if rendered is None:
        raise ValueError("Plain-text renderer input is missing")
    values = {**context, "rendered": rendered, "renderedResponse": rendered}
    if content is not None:
        values["content"] = content
    if report is not None:
        values["report"] = report
    if response is not None:
        values["response"] = response
    if agentownOutputContract:
        return _contract_result(values, agentownOutputContract)
    result = {"renderedResponse": rendered}
    if content is not None:
        result["content"] = content
    elif report is not None:
        result["report"] = report
    else:
        result["response"] = response
    return result


def workflow_end(**context: Any):
    return context


BUILTIN_TOOLS = {
    "data.csv.compare": data_csv_compare,
    "template.markdown.table": template_markdown_table,
    "quality.check": quality_check,
    "template.plain-text": template_plain_text,
    "workflow.end": workflow_end,
}
