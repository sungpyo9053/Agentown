import json
import pytest
import asyncio
from tframex.flows.flow_context import FlowContext
from tframex.models.primitives import Message
from agentown_tframex_adapter.adapter import StructuredParallelPattern
import unittest

from agentown_tframex_adapter.adapter import DefinitionError, _apply_input_bindings, _assert_semantic_success, _evaluate_branch_expression, _output_correction_message, _set_parallel_field
from agentown_tframex_adapter.capabilities import _matches_contract, data_deduplicate, data_normalize, quality_check, template_plain_text
from agentown_tframex_adapter.codex_llm import _json_schema


RECORDS_CONTRACT = [
    {
        "name": "records",
        "type": "array",
        "required": True,
        "minItems": 3,
        "maxItems": 3,
        "itemType": "object",
        "itemSchema": [
            {"name": "recordId", "type": "string", "required": True},
            {"name": "score", "type": "number", "required": True},
        ],
    }
]


def test_parallel_join_preserves_pre_split_analysis_but_not_unbound_branch_mutations():
    class Engine:
        async def call_agent(self, name, message, **kwargs):
            return Message(role="assistant", content=json.dumps({"part": name, "priorAnalysis": "unbound mutation"}))

    original = {"source": "original"}
    context = FlowContext(Message(role="assistant", content=json.dumps({
        "request": original, "priorAnalysis": {"evidence": "verified before split"},
    })), shared_data={"_agentown_initial_input": original})
    pattern = StructuredParallelPattern("split", ["a", "b"], task_result_bindings={
        name: [{"sourceField": "part", "targetField": name, "aggregationMode": "SET_FIELD"}]
        for name in ("a", "b")
    })
    result = asyncio.run(pattern.execute(context, Engine()))
    value = json.loads(result.current_message.content)
    assert value["priorAnalysis"] == {"evidence": "verified before split"}
    assert value["request"] == original
    assert (value["a"], value["b"]) == ("a", "b")


def test_parallel_scope_preserves_unpartitioned_lists_when_worker_count_differs():
    content = json.dumps({"feedbacks": ["F1", "F2", "F3"], "context": "three source records"})
    for index in (1, 2):
        result = json.loads(_apply_input_bindings(content, [], {
            "_agentownParallelIndex": index, "_agentownParallelSize": 2,
        }))
        assert result["_agentownAssignedInput"]["feedbacks"] == ["F1", "F2", "F3"]
    exact = json.loads(_apply_input_bindings(json.dumps({"feedbacks": ["F1", "F2"]}), [], {
        "_agentownParallelIndex": 2, "_agentownParallelSize": 2,
    }))
    assert exact["_agentownAssignedInput"]["feedbacks"] == "F2"


def test_codex_output_schema_preserves_nested_runtime_contract():
    schema = _json_schema(RECORDS_CONTRACT)

    assert schema["additionalProperties"] is False
    assert schema["required"] == ["records"]
    assert schema["properties"]["records"]["minItems"] == 3
    item = schema["properties"]["records"]["items"]
    assert item["type"] == "object"
    assert item["additionalProperties"] is False
    assert item["required"] == ["recordId", "score"]
    assert "uniqueItems" not in _json_schema([
        {"name": "items", "type": "array", "required": True, "itemType": "string", "uniqueItems": True},
    ])["properties"]["items"]
    uri_schema = _json_schema([
        {"name": "source", "type": "string", "required": True, "format": "uri"},
        {"name": "sources", "type": "array", "required": True, "itemType": "string", "itemFormat": "uri"},
    ])
    assert "format" not in uri_schema["properties"]["source"]
    assert "format" not in uri_schema["properties"]["sources"]["items"]


def test_codex_output_schema_uses_required_nullable_for_domain_optional_fields():
    schema = _json_schema([
        {"name": "requiredTitle", "type": "string", "required": True},
        {"name": "optionalContext", "type": "string", "required": False},
        {"name": "items", "type": "array", "required": True, "itemType": "object", "itemSchema": [
            {"name": "id", "type": "string", "required": True},
            {"name": "note", "type": "string", "required": False},
        ]},
    ])

    assert schema["required"] == ["requiredTitle", "optionalContext", "items"]
    assert schema["properties"]["optionalContext"]["type"] == ["string", "null"]
    item = schema["properties"]["items"]["items"]
    assert item["required"] == ["id", "note"]
    assert item["properties"]["note"]["type"] == ["string", "null"]


def test_runtime_contract_accepts_null_only_for_optional_fields():
    assert _matches_contract(None, {"name": "note", "type": "string", "required": False})
    assert not _matches_contract(None, {"name": "title", "type": "string", "required": True})


def test_branch_expression_evaluates_supported_length_comparison_only():
    assert _evaluate_branch_expression({"records": [1]}, "records.length > 0") is True
    assert _evaluate_branch_expression({"records": []}, "records.length > 0") is False
    assert _evaluate_branch_expression({"records": [1]}, "records.pop() > 0") is not True


def test_input_quality_validates_and_preserves_declared_workflow_input():
    records = [
        {"recordId": "r-1", "score": 10},
        {"recordId": "r-2", "score": 20},
        {"recordId": "r-3", "score": 30},
    ]

    result = quality_check(
        records=records,
        agentownInputContract=RECORDS_CONTRACT,
        agentownOutputContract=RECORDS_CONTRACT + [
            {"name": "qualityPassed", "type": "boolean", "required": True}
        ],
    )

    assert result == {"records": records, "qualityPassed": True}


def test_input_quality_rejects_nested_schema_violation_without_mock_success():
    result = quality_check(
        records=[
            {"recordId": "r-1", "score": 10},
            {"recordId": "r-2", "score": "not-a-number"},
            {"recordId": "r-3", "score": 30},
        ],
        agentownInputContract=RECORDS_CONTRACT,
        agentownOutputContract=RECORDS_CONTRACT + [
            {"name": "qualityPassed", "type": "boolean", "required": True}
        ],
    )

    assert result["qualityPassed"] is False
    assert result["records"][1]["score"] == "not-a-number"


def test_unrouted_quality_gate_fails_closed_instead_of_continuing():
    try:
        quality_check(
            records=[{"recordId": "r-1", "score": "invalid"}],
            agentownInputContract=RECORDS_CONTRACT,
            agentownFailClosed=True,
        )
    except ValueError as exc:
        assert str(exc) == "Quality contract validation failed"
    else:
        raise AssertionError("invalid quality input continued past a fail-closed gate")


def test_parallel_binding_projects_array_item_only_for_scalar_target_contract():
    records = [
        {"recordId": "r-1", "score": 10},
        {"recordId": "r-2", "score": 20},
        {"recordId": "r-3", "score": 30},
    ]
    content = json.dumps({"records": records})

    bound = json.loads(_apply_input_bindings(
        content,
        [{"sourceField": "records", "targetField": "record"}],
        {"_agentownParallelIndex": 2, "_agentownParallelSize": 3},
        [{
            "name": "record",
            "type": "object",
            "required": True,
        }],
    ))

    assert bound["record"] == records[1]
    assert bound["_agentownAssignedInput"]["records"] == records[1]


def test_parallel_binding_keeps_array_for_array_target_contract():
    records = [
        {"recordId": "r-1", "score": 10},
        {"recordId": "r-2", "score": 20},
        {"recordId": "r-3", "score": 30},
    ]
    content = json.dumps({"records": records})

    bound = json.loads(_apply_input_bindings(
        content,
        [{"sourceField": "records", "targetField": "records"}],
        {"_agentownParallelIndex": 2, "_agentownParallelSize": 3},
        RECORDS_CONTRACT,
    ))

    assert bound["records"] == records


def test_quality_contract_enforces_nested_operational_constraints():
    contract = [{
        "name": "rows", "type": "array", "required": True, "minItems": 2,
        "itemType": "object", "uniqueBy": "supplierId", "itemSchema": [
            {"name": "supplierId", "type": "string", "required": True, "minLength": 1},
            {"name": "inspectedAt", "type": "string", "required": True, "format": "date"},
            {"name": "defectRate", "type": "number", "required": True, "minimum": 0, "maximum": 100},
            {"name": "decision", "type": "string", "required": True, "enumValues": ["ACCEPTED", "REJECTED"]},
            {"name": "evidenceUrls", "type": "array", "required": True, "minItems": 1,
             "itemType": "string", "itemFormat": "uri"},
        ],
    }]
    invalid_rows = [
        {"supplierId": "same", "inspectedAt": "not-a-date", "defectRate": 101,
         "decision": "MAYBE", "evidenceUrls": ["not-a-url"]},
        {"supplierId": "same", "inspectedAt": "2026-09-06", "defectRate": 1,
         "decision": "ACCEPTED", "evidenceUrls": ["https://example.com/evidence"]},
    ]

    result = quality_check(rows=invalid_rows, agentownInputContract=contract)

    assert result["qualityPassed"] is False


def test_plain_text_renderer_accepts_structured_bound_fields():
    result = template_plain_text(analysis={"items": ["a"]}, review={"status": "ok"})

    assert json.loads(result["renderedResponse"]) == {"analysis": {"items": ["a"]}, "review": {"status": "ok"}}


def test_consumer_plain_text_scopes_declared_fields_and_formats_without_context_leak():
    result = template_plain_text(
        analysis={"근거": ["원문 A", "원문 B"]}, review="추가 확인 필요",
        request={"private_input": "not a final deliverable"}, _agentownParallelIndex=1,
        agentownRenderFields=["analysis", "review"], agentownHumanReadable=True,
    )
    assert result["renderedResponse"] == "analysis\n근거\n1. 원문 A\n2. 원문 B\n\nreview\n추가 확인 필요"
    assert "request" not in result["renderedResponse"]
    with pytest.raises(ValueError, match="bound input is missing"):
        template_plain_text(request="unbound", agentownRenderFields=["answer"], agentownHumanReadable=True)
    typed = template_plain_text(
        report={"summary": "원문 근거"}, review="검수 완료", content="unbound old content",
        agentownRenderFields=["report", "review"], agentownHumanReadable=True,
        agentownOutputContract=[
            {"name": "renderedResponse", "type": "string", "required": True},
            {"name": "report", "type": "object", "required": True,
             "objectSchema": [{"name": "summary", "type": "string", "required": True}]},
        ],
    )
    assert "unbound" not in typed["renderedResponse"]
    assert "원문 근거" in typed["renderedResponse"]
    assert typed["report"] == {"summary": "원문 근거"}
    labeled = template_plain_text(
        findings=[{"id": "C1", "evidence": "원문"}], agentownRenderFields=["findings"],
        agentownHumanReadable=True, agentownInputContract=[{
            "name": "findings", "description": "검토 결과", "itemSchema": [
                {"name": "id", "description": "대상 번호"}, {"name": "evidence", "description": "근거 문장"},
            ],
        }],
    )
    assert labeled["renderedResponse"] == "검토 결과\n1. 대상 번호\n   C1\n   \n   근거 문장\n   원문"


def test_plain_text_renderer_serializes_structured_named_content():
    result = template_plain_text(
        content={"items": ["a"]},
        agentownOutputContract=[{"name": "content", "type": "string", "required": True}],
    )

    assert json.loads(result["content"]) == {"items": ["a"]}


def test_plain_text_renderer_preserves_structured_field_when_contract_requires_object():
    content = {"items": ["a"]}
    result = template_plain_text(
        report=content,
        agentownOutputContract=[
            {"name": "report", "type": "object", "required": True,
             "objectSchema": [{"name": "items", "type": "array", "required": True, "itemType": "string"}]},
            {"name": "renderedResponse", "type": "string", "required": True},
        ],
    )

    assert result["report"] == content
    assert json.loads(result["renderedResponse"]) == content


def test_plain_text_renderer_maps_generic_content_back_to_required_structured_alias():
    content = {"items": ["a"]}
    result = template_plain_text(
        content=content,
        agentownOutputContract=[
            {"name": "report", "type": "object", "required": True,
             "objectSchema": [{"name": "items", "type": "array", "required": True, "itemType": "string"}]},
            {"name": "renderedResponse", "type": "string", "required": True},
        ],
    )

    assert result["report"] == content
    assert json.loads(result["renderedResponse"]) == content


def test_empty_negative_evidence_list_is_a_valid_success_result():
    _assert_semantic_success(
        {"reviewResults": [{"evidenceErrors": []}]},
        [{"name": "reviewResults", "type": "array", "required": True, "itemType": "object", "itemSchema": [
            {"name": "evidenceErrors", "type": "array", "required": True, "itemType": "string"},
        ]}],
        "Agent output",
    )


def test_parallel_set_field_accepts_same_pass_through_value_and_rejects_conflicts():
    joined = {}
    _set_parallel_field(joined, "incidentRecord", "same input")
    _set_parallel_field(joined, "incidentRecord", "same input")
    assert joined == {"incidentRecord": "same input"}
    try:
        _set_parallel_field(joined, "incidentRecord", "different input")
    except DefinitionError as exc:
        assert "conflicting values" in str(exc)
    else:
        raise AssertionError("conflicting parallel values were silently overwritten")


def test_output_retry_contains_original_input_invalid_output_and_exact_error():
    message = _output_correction_message(
        '{"record":"source"}', '{"result":{"unexpected":true}}', ValueError("unexpected field"),
    )
    value = json.loads(message.content)
    assert value["originalInput"] == {"record": "source"}
    assert value["previousInvalidOutput"] == '{"result":{"unexpected":true}}'
    assert value["validationError"] == "unexpected field"


def test_normalize_and_deduplicate_are_deterministic_and_preserve_input_fields():
    assert data_normalize(records=[{"id": " a ", "note": " ok "}]) == {
        "records": [{"id": "a", "note": "ok"}],
    }
    assert data_deduplicate(key="id", records=[{"id": "a"}, {"id": "a"}, {"id": "b"}]) == {
        "records": [{"id": "a"}, {"id": "b"}],
    }


class InputQualityAndParallelBindingTest(unittest.TestCase):
    def test_input_quality_validates_and_preserves_declared_workflow_input(self):
        test_input_quality_validates_and_preserves_declared_workflow_input()

    def test_input_quality_rejects_nested_schema_violation_without_mock_success(self):
        test_input_quality_rejects_nested_schema_violation_without_mock_success()

    def test_unrouted_quality_gate_fails_closed_instead_of_continuing(self):
        test_unrouted_quality_gate_fails_closed_instead_of_continuing()

    def test_parallel_binding_projects_array_item_only_for_scalar_target_contract(self):
        test_parallel_binding_projects_array_item_only_for_scalar_target_contract()

    def test_parallel_binding_keeps_array_for_array_target_contract(self):
        test_parallel_binding_keeps_array_for_array_target_contract()

    def test_quality_contract_enforces_nested_operational_constraints(self):
        test_quality_contract_enforces_nested_operational_constraints()

    def test_plain_text_renderer_accepts_structured_bound_fields(self):
        test_plain_text_renderer_accepts_structured_bound_fields()

    def test_empty_negative_evidence_list_is_a_valid_success_result(self):
        test_empty_negative_evidence_list_is_a_valid_success_result()

    def test_parallel_set_field_accepts_same_pass_through_value_and_rejects_conflicts(self):
        test_parallel_set_field_accepts_same_pass_through_value_and_rejects_conflicts()

    def test_output_retry_contains_original_input_invalid_output_and_exact_error(self):
        test_output_retry_contains_original_input_invalid_output_and_exact_error()

    def test_normalize_and_deduplicate_are_deterministic_and_preserve_input_fields(self):
        test_normalize_and_deduplicate_are_deterministic_and_preserve_input_fields()


if __name__ == "__main__":
    unittest.main()
