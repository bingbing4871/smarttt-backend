"""Read-only Linux/Docker/API preflight. Never logs raw inspect environment or auth.
Configuration inspection is NOT a resource-enforcement test.
"""
from local_docker import output as docker_output
import json
import os
from pathlib import Path
import platform
import subprocess
import urllib.error
import urllib.request


def main():
    if not __debug__:
        raise SystemExit('Do not disable validation with Python optimization')
    if platform.system() != 'Linux' or os.environ.get('OJ_LAB_DEDICATED_VM') != 'YES_DISPOSABLE_VM':
        raise SystemExit('Approved dedicated Linux VM required')
    marker = Path('/etc/smarttt-oj-disposable-vm')
    if not marker.is_file() or marker.read_text().strip() != 'SMARTTT_OJ_DEDICATED_VM_V1':
        raise SystemExit('Dedicated VM marker required')
    subprocess.run(['systemd-detect-virt', '--vm'], check=True, stdout=subprocess.DEVNULL)
    if subprocess.run(['systemd-detect-virt', '--container'], stdout=subprocess.DEVNULL).returncode == 0:
        raise SystemExit('Container is not an acceptable VM boundary')
    state = Path(os.environ.get('XDG_STATE_HOME', str(Path.home() / '.local/state'))) / 'smarttt-oj-sandbox'
    assert not state.is_symlink() and state.stat().st_uid == os.getuid() and state.stat().st_mode & 0o077 == 0
    assert tuple(int(v) for v in platform.release().split('.')[:2]) >= (6, 1), 'Kernel 6.1+ required for this procPeak acceptance recipe'
    name = os.environ.get('OJ_GOJUDGE_CONTAINER', 'smarttt-oj-disposable')
    # Raw inspect remains in memory; NEVER write env/command arrays into evidence.
    inspect = json.loads(docker_output(['inspect', name]))[0]
    assert inspect['Config']['Labels']['smarttt.oj.lab'] == 'disposable-v1'
    assert inspect['State']['Running']
    host = inspect['HostConfig']
    assert host['Privileged'] and host['CgroupnsMode'] == 'private'
    assert host['Memory'] == 2147483648 and host['PidsLimit'] == 512 and host['NanoCpus'] == 2000000000
    assert host['LogConfig']['Type'] == 'none'
    assert not inspect.get('Mounts'), 'No host mounts allowed in this recipe'
    ports = inspect['NetworkSettings']['Ports']
    assert ports == {'5050/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '5050'}]}
    networks = inspect['NetworkSettings']['Networks']
    assert list(networks) == ['smarttt-oj-disposable-net']
    network = json.loads(docker_output(['network', 'inspect', 'smarttt-oj-disposable-net']))[0]
    assert network['Internal'] and network['Labels']['smarttt.oj.lab'] == 'disposable-v1'
    args = inspect['Args']
    for flag in ['-no-fallback', '-silent', '-parallelism=1', '-net-share=false', '-seccomp-conf=/opt/seccomp.yaml']:
        assert flag in args, 'Missing mandatory sandbox flag'
    def execute(*cmd):
        return docker_output(['exec', name] + list(cmd)).decode('utf-8').strip()
    assert execute('/opt/go-judge', '--version') == 'v1.9.10'
    compiler = execute('/usr/bin/g++', '--version').splitlines()[0]
    namespaces = {ns: execute('/usr/bin/readlink', '/proc/1/ns/' + ns) for ns in ['net', 'mnt', 'pid', 'uts', 'ipc']}
    credential = state / 'auth.env'
    assert not credential.is_symlink() and credential.stat().st_uid == os.getuid()
    assert credential.stat().st_mode & 0o077 == 0
    value = credential.read_text().strip()
    assert value.startswith('ES_AUTH_TOKEN=')
    secret = value[len('ES_AUTH_TOKEN='):]
    assert len(secret) == 64 and all(c in '0123456789abcdef' for c in secret)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    def request(path, token=None):
        headers = {'Authorization': 'Bearer ' + token} if token else {}
        try:
            with opener.open(urllib.request.Request('http://127.0.0.1:5050' + path, headers=headers), timeout=5) as r:
                return r.status, r.read(1048576)
        except urllib.error.HTTPError as e:
            return e.code, b''
    assert request('/file')[0] == 401 and request('/file', 'deliberately-wrong')[0] == 401
    assert request('/file', secret)[0] == 200
    code, body = request('/version'); assert code == 200
    version = json.loads(body); assert version['buildVersion'] == 'v1.9.10' and version['os'] == 'linux'
    code, body = request('/config'); assert code == 200
    runner = json.loads(body)['runnerConfig']
    assert runner['cgroupType'] == 2
    assert {'cpu', 'memory', 'pids'}.issubset(set(runner['cgroupControllers']))
    evidence = dict(schema='smarttt-oj-preflight-v1', kernel=platform.release(), version=version['buildVersion'],
                    image=inspect['Image'], compiler=compiler, outerNamespaces=namespaces,
                    cgroupType=runner['cgroupType'], controllers=runner['cgroupControllers'], auth_required=True,
                    resource_enforcement='NOT_YET_TESTED', seccomp_enforcement='NOT_YET_TESTED')
    destination = state / 'preflight.json'
    assert not destination.is_symlink()
    destination.write_text(json.dumps(evidence, indent=2))
    print(json.dumps(evidence, indent=2))
    print('Preflight only; run real C++ probes before acceptance.')


if __name__ == '__main__':
    main()
