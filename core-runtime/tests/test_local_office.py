import json
import shutil
import subprocess
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError

import pytest

from agentown_tframex_adapter.office import LocalOffice, OfficeTrace


def test_office_tables_preserve_text_and_never_interpret_html():
    node = shutil.which('node')
    if not node:
        pytest.skip('Node required for the shipped office renderer test')
    html = (Path(__file__).parents[1] / 'agentown_tframex_adapter/office.html').read_text()
    renderer = html.split('function tableCells', 1)[1].split("document.querySelector('#save')", 1)[0]
    script = r'''
const assert = require('node:assert/strict');
class Element {
 constructor(tag) { this.tag=tag; this.children=[]; this.dataset={}; this.textContent=''; }
 append(...children) { this.children.push(...children); }
 replaceChildren() { this.children=[]; }
 set innerHTML(value) { throw Error('Untrusted HTML must never be interpreted'); }
}
const document={createElement:tag=>new Element(tag)};
''' + 'function tableCells' + renderer + r'''
const target=new Element('div');
const raw='Summary\n| Candidate | Cost |\n| --- | ---: |\n| A | 180000 |\n| B | <img src=x onerror=alert(1)> |\n\nUnknown';
renderOutput(target,raw);
assert.equal(target.dataset.raw,raw);
assert.deepEqual(target.children.map(item=>item.tag),['pre','table','pre']);
assert.equal(target.children[1].children[1].children[1].children[1].textContent,'<img src=x onerror=alert(1)>');
assert.equal(target.children[1].children[0].children[0].children[0].scope,'col');
const table=target.children[1];renderOutput(target,raw);assert.equal(target.children[1],table);
for(const raw of ['plain <script>alert(1)</script>', '| A | B |\n| --- | --- |\n| broken |', '| A | B |\n| --- | --- |', '| A \\| B | C |\n| --- | --- |\n| a | b |']) {
 renderOutput(target,raw);
 assert.equal(target.dataset.raw,raw);
 assert.equal(target.children.length,1);
 assert.equal(target.children[0].tag,'pre');
 assert.equal(target.children[0].textContent,raw);
}
renderOutput(target,'');
assert.equal(target.children.length,1);
assert.equal(target.children[0].textContent,'');
'''
    subprocess.run([node, '-e', script], check=True, capture_output=True, text=True)


@pytest.fixture
def office(tmp_path):
    (tmp_path / 'design-bundle.json').write_text(json.dumps({
        'proposal': {'name': 'Test company'},
        'agentDefinitions': [{'key': 'worker.a', 'name': 'Alice', 'role': 'Review', 'outputSchema': [{'name': 'result'}]}],
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


def test_view_projects_declared_output_without_changing_runtime_trace(office):
    trace = OfficeTrace(office)
    raw = json.dumps({'result': 'Actual answer', 'request': 'Original input', '_agentownParallelIndex': 1})
    trace.append({'kind': 'agent_end', 'agent': 'worker-a__review1', 'output': raw})
    assert json.loads(office.snapshot()['employees'][0]['output']) == {'result': 'Actual answer'}
    assert trace[0]['output'] == raw


def test_later_parallel_success_does_not_erase_prior_failure(office):
    office.record({'kind': 'agent_error', 'agent': 'worker-a__review1', 'error': 'real failure'})
    office.record({'kind': 'agent_start', 'agent': 'worker-a__review2'})
    office.record({'kind': 'agent_end', 'agent': 'worker-a__review2', 'output': '{"result":"success"}'})
    assert office.snapshot()['employees'][0]['status'] == 'FAILED'
    assert office.snapshot()['employees'][0]['output'] == 'real failure'
    office.record({'kind': 'agent_start', 'agent': 'worker-a__review1'})
    office.record({'kind': 'agent_end', 'agent': 'worker-a__review1', 'output': '{"result":"recovered"}'})
    assert office.snapshot()['employees'][0]['status'] == 'SUCCEEDED'


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
