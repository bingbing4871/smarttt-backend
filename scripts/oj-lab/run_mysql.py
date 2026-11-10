"""Own disposable mysqld only; never connects to an existing MySQL or reads project settings.
Requires preinstalled binaries. Does not install/start services or change system environment.
Secrets are generated per run, absent from command lines and Git; bootstrap file is ACL protected.
"""
import argparse
import json
import os
import re
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]


def cleanup_owned(process, shutdown_command, shutdown_env, bootstrap, log, run, summary):
    """Only receives the Popen created by this invocation; never discovers processes."""
    errors = []

    def attempt(step, operation):
        try:
            operation()
        except Exception as exc:
            # Exception messages may contain credentials or full command lines.
            errors.append(step + ':' + type(exc).__name__)

    def stop():
        if process is None:
            return
        if process.poll() is None:
            def shutdown():
                result = subprocess.run(shutdown_command, env=shutdown_env,
                                        capture_output=True, timeout=10)
                if result.returncode:
                    raise RuntimeError('Shutdown failed')
            attempt('shutdown', shutdown)
            try:
                process.wait(timeout=20)
            except Exception as exc:
                if not isinstance(exc, subprocess.TimeoutExpired):
                    errors.append('wait:' + type(exc).__name__)
                attempt('terminate', process.terminate)
                try:
                    process.wait(timeout=10)
                except Exception as exc:
                    if not isinstance(exc, subprocess.TimeoutExpired):
                        errors.append('wait-after-terminate:' + type(exc).__name__)
                    attempt('kill', process.kill)
                    attempt('wait-after-kill', lambda: process.wait(timeout=10))
        summary['own_mysql_stopped'] = process.poll() is not None
        if not summary['own_mysql_stopped']:
            raise RuntimeError('Owned process did not exit')

    try:
        attempt('process-cleanup', stop)
    finally:
        try:
            attempt('bootstrap-delete', lambda: bootstrap.unlink() if bootstrap.exists() else None)
        finally:
            try:
                attempt('log-close', log.close)
            finally:
                summary['cleanup_errors'] = errors
                summary['cleanup_ok'] = not errors
                summary['success'] = summary.get('checks_passed') is True and not errors
                attempt('summary-write', lambda: (run / 'summary.json').write_text(
                    json.dumps(summary, indent=2), encoding='utf-8'))
                summary['cleanup_ok'] = not errors
                summary['success'] = summary.get('checks_passed') is True and not errors
                if errors:
                    print('Cleanup failures: ' + ', '.join(errors), file=sys.stderr)
    return not errors


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--mysql-bin', required=True, type=Path)
    parser.add_argument('--port', type=int, default=33379)
    parser.add_argument('--maven', default=shutil.which('mvn') or shutil.which('mvn.cmd'))
    parser.add_argument('--with-judge', action='store_true', help='Requires operator-confirmed isolated sandbox via loopback/tunnel')
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535 or not args.maven:
        raise SystemExit('Valid unprivileged port and installed Maven required')
    if args.with_judge:
        from urllib.parse import urlsplit
        endpoint = urlsplit(os.environ.get('OJ_LAB_JUDGE_ENDPOINT', ''))
        if (os.environ.get('OJ_LAB_JUDGE_ISOLATED') != 'YES_DEDICATED_SANDBOX'
                or endpoint.scheme != 'http' or endpoint.hostname != '127.0.0.1'
                or endpoint.username or endpoint.password or endpoint.path or endpoint.query or endpoint.fragment
                or not endpoint.port or endpoint.port <= 1023):
            raise SystemExit('Explicit dedicated sandbox attestation and loopback endpoint required')
        with socket.create_connection(('127.0.0.1', endpoint.port), timeout=3):
            pass  # connectivity only; never taken as evidence of correct sandbox isolation
        token = os.environ.get('OJ_LAB_JUDGE_TOKEN', '')
        if not re.fullmatch(r'[a-f0-9]{64}', token):
            raise SystemExit('Sandbox bearer credential required from a secure local source')
        evidence_path = os.environ.get('OJ_LAB_SANDBOX_EVIDENCE', '')
        if not evidence_path:
            raise SystemExit('Real dedicated-VM preflight evidence file required')
        evidence = json.loads(Path(evidence_path).read_text(encoding='utf-8'))
        if (evidence.get('schema') != 'smarttt-oj-preflight-v1' or evidence.get('version') != 'v1.9.10'
                or evidence.get('auth_required') is not True or evidence.get('cgroupType') != 2
                or not {'cpu', 'memory', 'pids'}.issubset(set(evidence.get('controllers', [])))
                or not all(evidence.get('outerNamespaces', {}).get(n) for n in ('net', 'mnt', 'pid', 'uts', 'ipc'))):
            raise SystemExit('Incomplete or incompatible dedicated-VM preflight evidence')
    suffix = '.exe' if os.name == 'nt' else ''
    daemon, client = [args.mysql_bin.resolve() / (n + suffix) for n in ('mysqld', 'mysql')]
    if not all(p.is_file() for p in (daemon, client)):
        raise SystemExit('Existing MySQL binaries required; no automatic installation')
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', args.port))  # fail without touching an occupied port
    run = ROOT / 'smarttt-exam/target/oj-real-e2e' / ('mysql-' + uuid.uuid4().hex[:12])
    run.mkdir(parents=True)
    # Restrict the NEW directory before writing bootstrap credentials.
    if os.name == 'nt':
        sid = re.search(rb'S-1-[0-9-]+', subprocess.check_output(['whoami', '/user', '/fo', 'csv', '/nh'])).group().decode('ascii')
        subprocess.run(['icacls', str(run), '/inheritance:r', '/grant:r', '*' + sid + ':(OI)(CI)F'],
                       check=True, stdout=subprocess.DEVNULL)
    else:
        run.chmod(0o700)
    root_pw, reader_pw, student_pw, other_pw = [secrets.token_hex(24) for _ in range(4)]
    db = 'oj_lab_' + uuid.uuid4().hex[:12]
    env = os.environ.copy()
    env.update(OJ_LAB_CONFIRM='YES_DISPOSABLE_LOCAL_ONLY', OJ_LAB_MYSQL_PORT=str(args.port),
               OJ_LAB_MYSQL_DATABASE=db, OJ_LAB_ROOT_PASSWORD=root_pw, OJ_LAB_READER_PASSWORD=reader_pw,
               OJ_LAB_STUDENT_PASSWORD=student_pw, OJ_LAB_OTHER_PASSWORD=other_pw,
               MAVEN_OPTS='-Xms128m -Xmx512m -XX:MaxMetaspaceSize=256m -XX:+UseSerialGC')
    data = run / 'data'
    bootstrap = run / 'bootstrap.sql'
    daemon_process = None
    summary = {'environment': 'disposable local real MySQL; laboratory schema and policy',
               'real_judge_requested': args.with_judge, 'test_exit': None}
    log = (run / 'mysqld.log').open('wb')
    try:
        command = [str(daemon), '--no-defaults', '--basedir=' + str(args.mysql_bin.resolve().parent),
                   '--datadir=' + str(data), '--innodb-buffer-pool-size=64M', '--performance-schema=OFF']
        initialized = subprocess.run(command + ['--initialize-insecure'], stdout=log, stderr=log, timeout=120)
        if initialized.returncode:
            raise RuntimeError('Private datadir initialization failed; see mysqld.log')
        bootstrap.write_text("ALTER USER 'root'@'localhost' IDENTIFIED WITH mysql_native_password BY '" + root_pw + "';\n", encoding='ascii')
        daemon_process = subprocess.Popen(command + ['--bind-address=127.0.0.1', '--port=' + str(args.port),
            '--mysqlx=OFF', '--max-connections=10', '--innodb-log-buffer-size=8M',
            '--init-file=' + str(bootstrap)], stdout=log, stderr=log)
        mysql_env = env.copy()
        mysql_env['MYSQL_PWD'] = root_pw
        connection = [str(client), '--no-defaults', '--protocol=TCP', '--host=127.0.0.1', '--port=' + str(args.port),
                      '--user=root', '--connect-timeout=2', '--batch', '--skip-column-names']
        deadline = time.monotonic() + 60
        while True:
            if daemon_process.poll() is not None:
                raise RuntimeError('Private mysqld exited; see mysqld.log')
            ready = subprocess.run(connection + ['-e', 'SELECT 1'], env=mysql_env, capture_output=True)
            if ready.returncode == 0:
                break
            if time.monotonic() >= deadline:
                raise RuntimeError('Private mysqld readiness timed out')
            time.sleep(0.5)
        bootstrap.unlink()  # credentials no longer needed on disk
        schema = Path(__file__).with_name('schema.sql').read_text(encoding='utf-8')
        setup = (f'CREATE DATABASE `{db}`; USE `{db}`;\n'
                 f"SET @student_password='{student_pw}', @other_password='{other_pw}';\n" + schema +
                 f"\nCREATE USER 'oj_reader'@'127.0.0.1' IDENTIFIED WITH mysql_native_password BY '{reader_pw}';\n"
                 f"GRANT SELECT ON `{db}`.* TO 'oj_reader'@'127.0.0.1';\n")
        seed = subprocess.run(connection, input=setup.encode(), env=mysql_env, capture_output=True)
        if seed.returncode:
            raise RuntimeError('Fixture schema initialization failed (details withheld to protect credentials)')
        summary['mysql_version'] = subprocess.check_output([str(daemon), '--version'], text=True).strip()
        report_dir = run / 'surefire'
        cmd = [args.maven, '-B', '-ntp', '-f', 'smarttt-exam/pom.xml', '-P' + ('oj-real-e2e' if args.with_judge else 'oj-real-mysql'),
               '-DargLine=-Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC',
               '-Doj.lab.report-directory=' + str(report_dir), 'test']
        with (run / 'maven.log').open('wb') as output:
            result = subprocess.run(cmd, cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT, timeout=1200 if args.with_judge else 240)
        summary['test_exit'] = result.returncode
        # Build success alone is insufficient: independently require all actual cases.
        import xml.etree.ElementTree as ET
        cls = 'OjRealJudgeTests' if args.with_judge else 'OjRealMysqlTests'
        xml = report_dir / ('TEST-com.example.smartttexam.oj.integration.' + cls + '.xml')
        if not xml.exists():
            raise RuntimeError('Missing real MySQL Surefire XML; see maven.log')
        suite = ET.parse(xml).getroot()
        summary['tests'] = {k: int(suite.attrib[k]) for k in ('tests', 'failures', 'errors', 'skipped')}
        expected = 24 if args.with_judge else 8
        if result.returncode or summary['tests'] != dict(tests=expected, failures=0, errors=0, skipped=0):
            raise RuntimeError('Real MySQL checks failed; see isolated reports')
        summary['checks_passed'] = True
    finally:
        stop_env = os.environ.copy()
        stop_env['MYSQL_PWD'] = root_pw
        cleaned = cleanup_owned(daemon_process,
            [str(client), '--no-defaults', '--protocol=TCP', '--host=127.0.0.1',
             '--port=' + str(args.port), '--user=root', '--connect-timeout=2', '-e', 'SHUTDOWN'],
            stop_env, bootstrap, log, run, summary)
        print('Evidence:', run)
        print(json.dumps(summary, indent=2))
        if not cleaned and sys.exc_info()[0] is None:
            raise RuntimeError('Private MySQL cleanup failed; see sanitized evidence')


if __name__ == '__main__':
    main()
