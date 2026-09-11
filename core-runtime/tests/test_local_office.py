import json
from hashlib import sha256
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
        {'id': 'research', 'nodeType': 'local.web.research', 'label': '공개 자료 조사', 'config': {}},
    ]}))
    (tmp_path / 'company').mkdir()
    (tmp_path / 'company/index.html').write_text('<html>Local only</html>')
    viewer = LocalOffice(tmp_path)
    viewer.start()
    yield viewer
    viewer.close()


def test_artifact_download_uses_received_bytes_and_reports_failures():
    node = shutil.which('node')
    if not node:
        pytest.skip('Node required for the shipped office download test')
    html = (Path(__file__).parents[1] / 'agentown_tframex_adapter/office.html').read_text()
    handler = 'async function downloadArtifact' + html.split('async function downloadArtifact', 1)[1].split("document.querySelector('#artifact').onclick", 1)[0]
    script = r'''
const assert=require('node:assert/strict');
const error={hidden:true}, trigger={dataset:{},download:'agentown-result.zip',setAttribute(){},removeAttribute(){}};
let clicked=0,removed=0,revoked=0,received,fetches=0,prevented=0;
const blob={size:123};
let response={ok:true,blob:async()=>blob};
const fetch=async(path,options)=>{assert.equal(path,'artifact');assert.equal(options.cache,'no-store');fetches++;return response};
const document={querySelector:()=>error,body:{append(){}},createElement:()=>({style:{},click(){assert.equal(this.download,trigger.download);assert.equal(this.href,'blob:verified');clicked++},remove(){removed++}})};
const URL={createObjectURL(value){received=value;return 'blob:verified'},revokeObjectURL(value){assert.equal(value,'blob:verified');revoked++}};
const setTimeout=(fn,ms)=>{assert.equal(ms,60000);fn()};
''' + handler + r'''
(async()=>{
const event={currentTarget:trigger,preventDefault(){prevented++}};
const first=downloadArtifact(event);await downloadArtifact(event);await first;
assert.equal(fetches,1);assert.equal(clicked,1);assert.equal(removed,1);assert.equal(revoked,1);assert.equal(received,blob);assert.equal(prevented,2);assert.equal(trigger.dataset.busy,'false');assert.equal(error.hidden,true);
for(const invalid of [{ok:false},{ok:true,blob:async()=>({size:0})},{ok:true,blob:async()=>({size:64*1024*1024+1})},{ok:true,blob:async()=>{throw Error('network')}}]){
response=invalid;await downloadArtifact(event);assert.equal(clicked,1);assert.equal(error.hidden,false);assert.match(error.textContent,/다시/);assert.equal(trigger.dataset.busy,'false');
}
response={ok:true,blob:async()=>blob};await downloadArtifact(event);assert.equal(clicked,2);assert.equal(error.hidden,true);
})().catch(error=>{console.error(error);process.exitCode=1});
'''
    subprocess.run([node, '-e', script], check=True, capture_output=True, text=True)


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


def test_tool_progress_elapsed_time_and_errors_do_not_fake_employee_activity(office, monkeypatch):
    monkeypatch.setattr('agentown_tframex_adapter.office.time.monotonic', lambda: 10)
    office.record({'kind': 'agent_start', 'agent': 'local-web-research__research'})
    monkeypatch.setattr('agentown_tframex_adapter.office.time.monotonic', lambda: 27)
    assert office.snapshot()['activeSteps'] == [{'label': '공개 자료 조사', 'elapsedSeconds': 17}]
    assert office.snapshot()['employees'][0]['status'] == 'IDLE'
    office.record({'kind': 'agent_error', 'agent': 'local-web-research__research', 'error': 'Sources unavailable'})
    assert office.snapshot()['activeSteps'] == []
    assert office.snapshot()['error'] == 'Sources unavailable'
    office.record({'kind': 'agent_start', 'agent': 'local-web-research__research'})
    assert office.snapshot()['error'] == ''
    office.finish('FAILED', 'Execution failed before an agent could start')
    assert office.snapshot()['activeSteps'] == []
    assert office.snapshot()['error'] == 'Execution failed before an agent could start'


def test_only_verified_final_artifact_is_downloadable_and_bytes_are_immutable(office, tmp_path):
    result = office.results_root / 'run' / 'result.zip'
    result.parent.mkdir(parents=True)
    content = b'generated artifact fixture'
    result.write_bytes(content)
    output = {'artifactPath': str(result), 'artifactBytes': len(content), 'artifactSha256': sha256(content).hexdigest()}
    office.finish('SUCCEEDED', output=output)
    assert office.snapshot()['artifact'] == {'name': 'agentown-result.zip', 'bytes': len(content)}
    result.write_bytes(b'changed after completion')
    with urlopen(office.url + 'artifact') as response:
        assert response.read() == content
        assert response.headers['Content-Disposition'] == 'attachment; filename="agentown-result.zip"'
    outside = tmp_path / 'private.zip'
    outside.write_bytes(content)
    result.write_bytes(content)
    for invalid in ({**output, 'artifactPath': str(outside)}, {**output, 'artifactSha256': 'wrong'}):
        office.finish('SUCCEEDED', output=invalid)
        assert 'artifact' not in office.snapshot()
        assert office.snapshot()['artifactError']
        with pytest.raises(HTTPError) as error:
            urlopen(office.url + 'artifact')
        assert error.value.code == 404


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
    assert error.value.code == 403


def test_interactive_input_is_validated_once_and_never_echoed_in_state(office):
    office.input_schema = {'type': 'object', 'properties': {'text': {'type': 'string', 'minLength': 1}},
                           'required': ['text'], 'additionalProperties': False}
    origin = office.url.split('/')[0] + '//' + office.url.split('/')[2]
    headers = {'Origin': origin, 'Content-Type': 'application/json'}
    assert office.snapshot()['inputSchema'] == office.input_schema
    assert not office.input_ready.is_set()
    for value in ({}, {'text': 3}, {'text': ''}, {'text': 'ok', 'extra': True}, []):
        with pytest.raises(HTTPError) as error:
            urlopen(Request(office.url + 'input', data=json.dumps(value).encode(), headers=headers))
        assert error.value.code == 422
        assert not office.input_ready.is_set()
    with urlopen(Request(office.url + 'input', data=b'{"text":"private input"}', headers=headers)) as response:
        assert response.status == 204
    assert office.input_ready.is_set()
    assert office.submitted_input == {'text': 'private input'}
    assert office.snapshot()['status'] == 'RUNNING'
    assert 'inputSchema' not in office.snapshot()
    assert 'private input' not in json.dumps(office.snapshot())
    with pytest.raises(HTTPError) as error:
        urlopen(Request(office.url + 'input', data=b'{"text":"duplicate"}', headers=headers))
    assert error.value.code == 409
    assert office.submitted_input == {'text': 'private input'}


def test_input_form_preserves_types_drafts_and_prevents_duplicate_calls():
    node = shutil.which('node')
    if not node:
        pytest.skip('Node required for the shipped input form test')
    html = (Path(__file__).parents[1] / 'agentown_tframex_adapter/office.html').read_text()
    handler = 'inputForm.onsubmit=async event=>{' + html.split('inputForm.onsubmit=async event=>{', 1)[1].split('function readable', 1)[0]
    script = r'''
const assert=require('node:assert/strict');
const error={hidden:true},button={disabled:false};
const inputForm={dataset:{},hidden:false,querySelector:()=>button};
const document={querySelector:()=>error};
let inputSubmitted=false,calls=0,received,response={ok:false,status:422};
const fetch=async(path,options)=>{assert.equal(path,'input');assert.equal(options.method,'POST');assert.equal(options.headers['Content-Type'],'application/json');calls++;received=JSON.parse(options.body);return response};
const field=(key,type,value,required=true)=>({key,field:{type},control:{value,required}});
let inputControls=[field('text','string',' original '),field('count','integer','0'),field('enabled','boolean','false'),field('rows','array','["one"]'),field('optional','string','',false)];
''' + handler + r'''
(async()=>{
 const event={preventDefault(){}};
 await inputForm.onsubmit(event);
 assert.deepEqual(received,{text:' original ',count:0,enabled:false,rows:['one']});
 assert.equal(inputControls[0].control.value,' original ');assert.equal(inputForm.hidden,false);assert.equal(error.hidden,false);assert.equal(button.disabled,false);
 inputControls[3].control.value='bad json';await inputForm.onsubmit(event);assert.equal(calls,1);assert.equal(error.hidden,false);
 inputControls[3].control.value='[]';response={ok:true};
 const first=inputForm.onsubmit(event);await inputForm.onsubmit(event);await first;
 assert.equal(calls,2);assert.equal(inputSubmitted,true);assert.equal(inputForm.hidden,true);
 await inputForm.onsubmit(event);assert.equal(calls,2);
})().catch(error=>{console.error(error);process.exitCode=1});
'''
    subprocess.run([node, '-e', script], check=True, capture_output=True, text=True)


def test_interactive_input_rejects_cross_origin_wrong_routes_and_large_payloads(office):
    origin = office.url.split('/')[0] + '//' + office.url.split('/')[2]
    headers = {'Origin': origin, 'Content-Type': 'application/json'}
    with pytest.raises(HTTPError) as error:
        urlopen(Request(office.url + 'input', data=b'{}', headers=headers))
    assert error.value.code == 404  # Existing viewer-only mode has no input endpoint.
    office.input_schema = {'type': 'object'}
    for path, changes, expected in [('input', {'Origin': 'https://attacker.example'}, 403),
                                   ('input', {'Origin': 'null'}, 403),
                                   ('input', {'Host': 'attacker.example'}, 403),
                                   ('wrong', {}, 404),
                                   ('input', {'Content-Type': 'text/plain'}, 415),
                                   ('input', {'Content-Length': str(1024 * 1024 + 1)}, 413)]:
        with pytest.raises(HTTPError) as error:
            urlopen(Request(office.url + path, data=b'{}', headers={**headers, **changes}))
        assert error.value.code == expected
        assert not office.input_ready.is_set()
