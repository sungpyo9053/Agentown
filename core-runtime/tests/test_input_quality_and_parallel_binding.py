import json
import unittest

from agentown_tframex_adapter.adapter import _apply_input_bindings
from agentown_tframex_adapter.capabilities import quality_check


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


if __name__ == "__main__":
    unittest.main()
