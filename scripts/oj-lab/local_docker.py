"""Single Docker transport boundary. Never consult the user's Docker context."""
import os
from pathlib import Path
import platform
import stat
import subprocess
import sys

SOCKET = '/var/run/docker.sock'
OVERRIDES = ('DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_TLS_VERIFY',
             'DOCKER_CERT_PATH', 'DOCKER_CONFIG', 'BUILDX_BUILDER', 'BUILDKIT_HOST')

def approved_vm():
    if platform.system() != 'Linux' or os.environ.get('OJ_LAB_DEDICATED_VM') != 'YES_DISPOSABLE_VM':
        raise RuntimeError('Approved dedicated Linux VM required')
    marker = Path('/etc/smarttt-oj-disposable-vm')
    if marker.read_text().strip() != 'SMARTTT_OJ_DEDICATED_VM_V1':
        raise RuntimeError('Dedicated VM marker required')
    subprocess.run(['systemd-detect-virt', '--vm'], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5)
    if subprocess.run(['systemd-detect-virt', '--container'],
                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5).returncode == 0:
        raise RuntimeError('A container is not a dedicated VM')


def command(arguments):
    if any(name in os.environ for name in OVERRIDES):
        raise RuntimeError('Docker target/configuration overrides must be unset')
    approved_vm()  # The helper itself cannot bypass the shell entry-point gates.
    if not stat.S_ISSOCK(Path(SOCKET).stat().st_mode):
        raise RuntimeError('Local Docker Unix socket required')
    if not os.access(SOCKET, os.R_OK | os.W_OK):
        raise RuntimeError('Local Docker Unix socket access denied')
    # Explicit host takes precedence over the stored currentContext. Force the
    # classic builder so buildx cannot select an independently configured node.
    env = os.environ.copy()
    env['DOCKER_BUILDKIT'] = '0'
    base = ['docker', '--host', 'unix://' + SOCKET]
    result = subprocess.run(base + ['info', '--format', '{{.OSType}}'], env=env,
                            check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            timeout=10)
    if result.stdout.strip() != b'linux':
        raise RuntimeError('Local Docker daemon validation failed')
    return base + list(arguments), env


def output(arguments):
    argv, env = command(arguments)
    return subprocess.check_output(argv, env=env, timeout=30)


def main():
    try:
        argv, env = command(sys.argv[1:])
        return subprocess.call(argv, env=env)
    except (OSError, RuntimeError, subprocess.SubprocessError):
        print('Local Docker validation/operation failed; refusing operation', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
