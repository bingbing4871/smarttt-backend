"""Real HTTP assertions against the active, disposable browser lab; no mock executor.
Run only while run_mysql.py --browser-classpath is running. Mutates its PRIVATE
catalog and fixture membership, then restores publication/membership in finally.
Passwords/tokens stay in memory; sanitized evidence excludes bodies and headers.
"""
import json
from pathlib import Path
import time
import urllib.request
import urllib.error

ROOT = Path(__file__).resolve().parents[2]
BASE = 'http://127.0.0.1:45782'


def main():
    lab = Path(json.loads((ROOT / 'smarttt-exam/target/oj-unified/active-lab.json').read_text())['directory']).resolve()
    if (ROOT / 'smarttt-exam/target/oj-real-e2e').resolve() not in lab.parents or not (lab / 'ready.json').is_file():
        raise RuntimeError('Active private disposable lab required')
    credentials = json.loads((lab / 'credentials.json').read_text(encoding='utf-8'))
    evidence = []
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def call(method, path, token=None, body=None, expected=200):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        req = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
        try:
            response = opener.open(req, timeout=25)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read(1048577)
            assert len(raw) < 1048576
            status = response.code
        evidence.append({'method': method, 'path': path, 'status': status})
        assert status == expected, (method, path, status, expected)
        value = json.loads(raw)
        if not path.endswith('/login'):
            forbidden = {'testCases', 'expectedOutput', 'recordChecksum', 'fingerprint', 'sourceSha256', 'fileIds', 'stdout', 'stderr', 'storage-directory'}
            def inspect(node):
                if isinstance(node, dict):
                    assert not forbidden.intersection(node)
                    for child in node.values():
                        inspect(child)
                elif isinstance(node, list):
                    for child in node:
                        inspect(child)
            inspect(value)
        return value

    def control(action):
        result = lab / 'command-result.json'
        result.unlink(missing_ok=True)
        temporary = lab / 'command.tmp'
        temporary.write_text(json.dumps({'action': action}), encoding='utf-8')
        temporary.replace(lab / 'command.json')
        deadline = time.monotonic() + 30
        while not result.exists():
            if time.monotonic() > deadline:
                raise RuntimeError('Owned Java control timed out')
            time.sleep(0.1)
        assert json.loads(result.read_text())['action'] == action

    def snapshots():
        return [json.loads(p.read_text(encoding='utf-8')) for p in (lab / 'submissions').rglob('*.json')]

    def find(record):
        return next(item for item in snapshots() if item['submissionId'] == record['submissionId'])

    prefix = '/api/exam/oj/submissions'
    scope = '?courseId=lab-course&classroomId=lab-class'
    token = call('POST', '/api/exam/oj/login', body={'login': credentials['login'], 'password': credentials['password']})['token']
    bob = call('POST', '/api/exam/oj/login', body={'login': credentials['otherLogin'], 'password': credentials['otherPassword']})['token']
    call('POST', '/api/exam/oj/login', body={'login': 'alice', 'password': 'incorrect'}, expected=401)
    call('GET', prefix + scope, expected=401)
    assert len(call('GET', prefix + '/problems' + scope, token)) == 2
    other = '?courseId=other-course&classroomId=other-class'
    assert [x['problemId'] for x in call('GET', prefix + '/problems' + other, token)] == ['oj-other-scope']
    request = {'requestId': 'unified-version-1', 'courseId': 'lab-course', 'classroomId': 'lab-class', 'problemId': 'oj-fixture-add', 'language': 'cpp17', 'sourceCode': '#include <iostream>\nint main(){long long a,b;std::cin>>a>>b;std::cout<<a+b;}'}
    first = call('POST', prefix, token, request)
    assert first['status'] == 'WAITING_JUDGE' and 'judgement' not in first
    assert call('POST', prefix, token, request)['submissionId'] == first['submissionId']
    call('POST', prefix, token, {**request, 'sourceCode': 'int main(){}'}, expected=409)
    call('POST', prefix, token, {**request, 'requestId': 'forged-student', 'studentId': 'student-b'}, expected=400)
    call('POST', prefix, token, {**request, 'requestId': 'wrong-course', 'courseId': 'other-course'}, expected=403)
    call('POST', prefix, token, {**request, 'requestId': 'wrong-class', 'classroomId': 'other-class'}, expected=403)
    call('POST', prefix, token, {**request, 'requestId': 'wrong-problem', 'problemId': 'oj-other-scope'}, expected=400)
    call('GET', prefix + scope, bob, expected=403)
    control('join-bob')
    assert call('GET', prefix + scope, bob) == []
    call('GET', prefix + '/' + first['submissionId'] + scope, bob, expected=404)
    original = (lab / 'catalog.json').read_bytes()
    revoked = False
    success = False
    try:
        document = json.loads(original)
        problem = document['problems'][0]['problem']
        problem['version'] = '2'
        problem['testVersion'] = '2'
        problem['statement'] += '\n隔离联调修订版本二。'
        problem['testCases'].append({'caseId': 'unified-revision', 'input': '3 4\n', 'expectedOutput': '7\n'})
        (lab / 'catalog.json').write_text(json.dumps(document, ensure_ascii=False), encoding='utf-8')
        control('restart')
        second = call('POST', prefix, token, {**request, 'requestId': 'unified-version-2'})
        assert find(first)['problem']['version'] == '1' and find(first)['problem']['testVersion'] == '1'
        assert find(second)['problem']['version'] == '2' and find(second)['problem']['testVersion'] == '2'
        assert second['attemptNumber'] == first['attemptNumber'] + 1
        assert call('POST', prefix, token, request)['submissionId'] == first['submissionId']
        document['problems'][0]['published'] = False
        (lab / 'catalog.json').write_text(json.dumps(document, ensure_ascii=False), encoding='utf-8')
        control('restart')
        assert [x['problemId'] for x in call('GET', prefix + '/problems' + scope, token)] == ['oj-lab-multiply']
        before = len(snapshots())
        call('POST', prefix, token, {**request, 'requestId': 'after-unpublish'}, expected=400)
        assert len(snapshots()) == before
        assert call('POST', prefix, token, request)['submissionId'] == first['submissionId']
        assert call('GET', prefix + '/' + first['submissionId'] + scope, token)['submissionId'] == first['submissionId']
        assert any(x['submissionId'] == first['submissionId'] for x in call('GET', prefix + scope, token))
        control('revoke')
        revoked = True
        call('POST', prefix, token, {**request, 'requestId': 'revoked'}, expected=403)
        call('GET', prefix + scope, token, expected=403)
        call('GET', prefix + '/scope?classroomId=lab-class', token, expected=403)
        assert len(snapshots()) == before
        assert all(x['status'] == 'WAITING_JUDGE' for x in snapshots())
        assert not (lab / 'results').exists() or not list((lab / 'results').rglob('*.json'))
        success = True
    finally:
        try:
            (lab / 'catalog.json').write_bytes(original)
            control('restart')
        finally:
            try:
                if revoked:
                    control('restore')
            finally:
                (lab / 'http-scenarios.json').write_text(json.dumps({'success': success, 'requests': evidence,
                    'real_judge': False, 'schema': 'disposable fixture only'}, indent=2), encoding='utf-8')
    print('Real Java/MySQL HTTP scenarios passed; requests:', len(evidence), '; no judge executed')


if __name__ == '__main__':
    main()
