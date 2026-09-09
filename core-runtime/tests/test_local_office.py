import json
from urllib.request import Request, urlopen
from urllib.error import HTTPError

import pytest

from agentown_tframex_adapter.office import LocalOffice, OfficeTrace


@pytest.fixture
def office(tmp_path):
    (tmp_path / 'design-bundle.json').write_text(json.dumps({
        'proposal': {'name': 'Test company'},
        'agentDefinitions': [{'key': 'worker.a', 'name': 'Alice', 'role': 'Review'}],
    }))
    (tmp_path / 'workflow.json').write_text(json.dumps({'nodes': [
        {'id': 'review1', 'config': {'agentKey': 'worker.a'}},
        {'id': 'review2', 'config': {'agentKey': 'worker.a'}},
    ]}))
    (tmp_path / 'company').mkdir()
    (tmp_path / 'company/index.html').write_text('<html>Local only</html>')
    viewer = LocalOffice(tmp_path)
    viewer.start()
    yield viewer
    viewer.close()


def test_real_trace_mapping_and_parallel_failure(office):
    trace = OfficeTrace(office)
    trace.append({'kind': 'agent_start', 'agent': 'worker-a__review1'})
    trace.append({'kind': 'agent_start', 'agent': 'worker-a__review2'})
    trace.append({'kind': 'agent_end', 'agent': 'worker-a__review1', 'output': 'one'})
    assert office.snapshot()['employees'][0]['status'] == 'RUNNING'
    trace.append({'kind': 'agent_error', 'agent': 'worker-a__review2', 'error': 'invalid output'})
    assert office.snapshot()['employees'][0]['status'] == 'FAILED'
    assert len(trace) == 4  # Observer preserves the runtime's original failure trace.
    office.finish('FAILED')
    assert office.snapshot()['status'] == 'FAILED'


def test_unknown_nodes_do_not_animate_employees_and_cancel_is_honest(office):
    office.record({'kind': 'agent_start', 'agent': 'unknown'})
    assert office.snapshot()['employees'][0]['status'] == 'IDLE'
    office.record({'kind': 'agent_start', 'agent': 'worker-a__review1'})
    office.finish('INTERRUPTED')
    assert office.snapshot()['employees'][0]['status'] == 'INTERRUPTED'


def test_loopback_capability_no_file_serving_or_writes(office):
    assert office.server.server_address[0] == '127.0.0.1'
    with urlopen(office.url + 'state') as response:
        assert json.load(response)['name'] == 'Test company'
        assert response.headers['Cache-Control'] == 'no-store'
        assert response.headers.get('Access-Control-Allow-Origin') is None
    for path in ('../runtime-definition.json', '../.env', 'run', '?path=/etc/passwd'):
        with pytest.raises(HTTPError) as error:
            urlopen(office.url + path)
        assert error.value.code == 404
    with pytest.raises(HTTPError) as error:
        urlopen(Request(office.url, headers={'Host': 'attacker.example'}))
    assert error.value.code == 403
    with pytest.raises(HTTPError) as error:
        urlopen(Request(office.url, data=b'run', method='POST'))
    assert error.value.code == 501
