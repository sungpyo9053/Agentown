from __future__ import annotations

import csv
import io
from typing import Any


def _rows(value: Any) -> list[dict[str, str]]:
    if isinstance(value, list):
        return [{str(key): "" if item is None else str(item) for key, item in row.items()} for row in value if isinstance(row, dict)]
    if isinstance(value, str) and value.strip():
        return [dict(row) for row in csv.DictReader(io.StringIO(value))]
    return []


def data_csv_compare(csvA: Any, csvB: Any, keyColumns: list[str] | None = None, **context: Any):
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
    return {**context, "csvA": csvA, "csvB": csvB, "changedRows": changes}


def template_markdown_table(changedRows: list[dict[str, Any]], **context: Any):
    lines = ["| changeType | key |", "|---|---|"]
    lines.extend(f"| {row.get('changeType', '')} | {', '.join(row.get('key', []))} |" for row in changedRows)
    return {**context, "changedRows": changedRows, "rendered": "\n".join(lines)}


def quality_check(**context: Any):
    missing = context.get("missingLocations") or context.get("missingFields") or []
    failures = context.get("failures") or context.get("errors") or []
    status = str(context.get("reportStatus") or context.get("status") or "").upper()
    passed = not missing and not failures and status in {"READY", "SUCCEEDED", "COMPLETED"}
    return {**context, "qualityPassed": passed}


def template_plain_text(content: Any = None, report: Any = None, response: Any = None, **context: Any):
    rendered = content if content is not None else report if report is not None else response
    if rendered is None:
        raise ValueError("Plain-text renderer input is missing")
    return {**context, "content": content, "report": report, "renderedResponse": rendered, "response": rendered}


BUILTIN_TOOLS = {
    "data.csv.compare": data_csv_compare,
    "template.markdown.table": template_markdown_table,
    "quality.check": quality_check,
    "template.plain-text": template_plain_text,
}
