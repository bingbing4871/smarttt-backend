"""No daemon operations: all Docker and process operations are mocked."""
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

import local_docker
import run_mysql


class DockerSafetyTests(unittest.TestCase):
    def setUp(self):
        self.env = patch.dict(os.environ, {}, clear=True)
        self.env.start()
        self.addCleanup(self.env.stop)
        guard = patch('local_docker.approved_vm')
        guard.start()
        self.addCleanup(guard.stop)

    def test_remote_overrides_rejected_before_docker(self):
        for name in local_docker.OVERRIDES:
            with self.subTest(name=name), patch.dict(os.environ, {name: 'remote'}), patch('local_docker.subprocess.run') as run:
                with self.assertRaises(RuntimeError):
                    local_docker.command(['run'])
                run.assert_not_called()

    def test_missing_socket(self):
        with patch.object(Path, 'stat', side_effect=FileNotFoundError), patch('local_docker.subprocess.run') as run:
            with self.assertRaises(FileNotFoundError):
                local_docker.command(['rm'])
            run.assert_not_called()

    def test_non_socket(self):
        with patch.object(Path, 'stat', return_value=Mock(st_mode=stat.S_IFREG)):
            with self.assertRaises(RuntimeError):
                local_docker.command(['stop'])

    def test_permission_denied(self):
        with patch.object(Path, 'stat', return_value=Mock(st_mode=stat.S_IFSOCK)), patch('os.access', return_value=False):
            with self.assertRaises(RuntimeError):
                local_docker.command(['inspect'])

    def test_remote_current_context_is_ignored_for_all_operations(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / 'config.json').write_text(json.dumps({'currentContext': 'remote-production'}))
            with patch.dict(os.environ, {'HOME': tmp}), patch.object(Path, 'stat', return_value=Mock(st_mode=stat.S_IFSOCK)), patch('os.access', return_value=True), patch('local_docker.subprocess.run', return_value=Mock(stdout=b'linux\n')) as run:
                for operation in ('build', 'run', 'stop', 'rm', 'inspect', 'exec', 'network'):
                    argv, env = local_docker.command([operation])
                    self.assertEqual(argv, ['docker', '--host', 'unix:///var/run/docker.sock', operation])
                    self.assertEqual(env['DOCKER_BUILDKIT'], '0')
                self.assertTrue(all(c.args[0][:3] == argv[:3] for c in run.call_args_list))

    def test_failed_target_validation(self):
        for result in (Mock(stdout=b'windows'), subprocess.TimeoutExpired('docker', 10), subprocess.CalledProcessError(1, 'docker')):
            with self.subTest(result=type(result).__name__), patch.object(Path, 'stat', return_value=Mock(st_mode=stat.S_IFSOCK)), patch('os.access', return_value=True), patch('local_docker.subprocess.run') as run:
                if isinstance(result, Exception):
                    run.side_effect = result
                else:
                    run.return_value = result
                with self.assertRaises((RuntimeError, subprocess.SubprocessError)):
                    local_docker.command(['run'])

    def test_failed_validation_never_invokes_operation(self):
        with patch('local_docker.command', side_effect=RuntimeError('private')), patch('local_docker.subprocess.call') as call, patch('sys.stderr', new_callable=io.StringIO):
            self.assertEqual(local_docker.main(), 1)
            call.assert_not_called()

    def test_every_script_uses_boundary(self):
        root = Path(__file__).parent
        sandbox = (root / 'sandbox.sh').read_text()
        self.assertNotIn('$(docker ', sandbox)
        self.assertNotIn('    docker ', sandbox)
        self.assertIn('local_docker.py', sandbox)
        self.assertIn('local_docker.py', (root / 'preflight_linux.sh').read_text())
        self.assertNotIn("['docker',", (root / 'inspect_sandbox.py').read_text())


class VmGuardTests(unittest.TestCase):
    def test_windows_refused_without_docker(self):
        with patch('local_docker.platform.system', return_value='Windows'), patch('local_docker.subprocess.run') as run:
            with self.assertRaises(RuntimeError):
                local_docker.approved_vm()
            run.assert_not_called()

    def test_unapproved_linux_refused(self):
        with patch('local_docker.platform.system', return_value='Linux'), patch.dict(os.environ, {}, clear=True), patch('local_docker.subprocess.run') as run:
            with self.assertRaises(RuntimeError):
                local_docker.approved_vm()
            run.assert_not_called()

    def test_invalid_marker_refused(self):
        with patch('local_docker.platform.system', return_value='Linux'), patch.dict(os.environ, {'OJ_LAB_DEDICATED_VM': 'YES_DISPOSABLE_VM'}), patch.object(Path, 'read_text', return_value='invalid'), patch('local_docker.subprocess.run') as run:
            with self.assertRaises(RuntimeError):
                local_docker.approved_vm()
            run.assert_not_called()

    def test_container_boundary_refused(self):
        with patch('local_docker.platform.system', return_value='Linux'), patch.dict(os.environ, {'OJ_LAB_DEDICATED_VM': 'YES_DISPOSABLE_VM'}), patch.object(Path, 'read_text', return_value='SMARTTT_OJ_DEDICATED_VM_V1'), patch('local_docker.subprocess.run', return_value=Mock(returncode=0)):
            with self.assertRaises(RuntimeError):
                local_docker.approved_vm()


class MysqlCleanupTests(unittest.TestCase):
    def exercise(self, shutdown=None, waits=None, delete_error=False, write_error=False, already_stopped=False, log_error=False):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bootstrap = root / 'bootstrap.sql'
            bootstrap.write_text('fixture')
            process = Mock()
            process.poll.side_effect = [0, 0] if already_stopped else [None, None if waits and len(waits) == 3 else 0]
            process.wait.side_effect = waits
            log = Mock()
            if log_error:
                log.close.side_effect = OSError('private')
            summary = {'test_exit': 0, 'checks_passed': True}
            original_unlink, original_write = Path.unlink, Path.write_text
            def unlink(path, *a, **kw):
                if delete_error and path == bootstrap:
                    raise PermissionError('private')
                return original_unlink(path, *a, **kw)
            def write(path, *a, **kw):
                if write_error and path.name == 'summary.json':
                    raise OSError('private')
                return original_write(path, *a, **kw)
            with patch('run_mysql.subprocess.run', side_effect=shutdown, return_value=Mock(returncode=0)) as run, patch.object(Path, 'unlink', unlink), patch.object(Path, 'write_text', write), patch('sys.stderr', new_callable=io.StringIO) as stderr:
                ok = run_mysql.cleanup_owned(process, ['fixture-client', 'SHUTDOWN'], {}, bootstrap, log, root, summary)
            log.close.assert_called_once()
            self.assertEqual(bootstrap.exists(), delete_error)
            self.assertEqual((root / 'summary.json').exists(), not write_error)
            self.assertEqual(ok, not summary['cleanup_errors'])
            self.assertEqual(summary['success'], ok)
            self.assertNotIn('private', stderr.getvalue())
            if not write_error:
                self.assertEqual(json.loads((root / 'summary.json').read_text())['success'], ok)
            return ok, summary, process, run

    def test_shutdown_timeout(self):
        self.assertFalse(self.exercise(shutdown=subprocess.TimeoutExpired('fixture', 10))[0])

    def test_shutdown_exception(self):
        self.assertFalse(self.exercise(shutdown=OSError('private'))[0])

    def test_normal_exit(self):
        ok, _, process, _ = self.exercise()
        self.assertTrue(ok)
        process.terminate.assert_not_called()
        process.kill.assert_not_called()

    def test_already_exited(self):
        ok, _, _, run = self.exercise(already_stopped=True)
        self.assertTrue(ok)
        run.assert_not_called()

    def test_bounded_termination(self):
        ok, _, process, _ = self.exercise(waits=[subprocess.TimeoutExpired('fixture', 20), 0])
        self.assertTrue(ok)
        process.terminate.assert_called_once()
        process.kill.assert_not_called()

    def test_unkillable_owned_process(self):
        ok, summary, process, _ = self.exercise(waits=[subprocess.TimeoutExpired('fixture', 20)] * 3)
        self.assertFalse(ok)
        process.kill.assert_called_once()
        self.assertFalse(summary['own_mysql_stopped'])

    def test_bootstrap_delete_failure(self):
        self.assertFalse(self.exercise(delete_error=True)[0])

    def test_summary_write_failure(self):
        self.assertFalse(self.exercise(write_error=True)[0])

    def test_log_failure_does_not_skip_summary(self):
        self.assertFalse(self.exercise(log_error=True)[0])

    def test_wait_exception_still_attempts_termination(self):
        ok, _, process, _ = self.exercise(waits=[OSError('private'), 0])
        self.assertFalse(ok)
        process.terminate.assert_called_once()

    def test_combined_failures_do_not_skip_final_steps(self):
        ok, summary, _, _ = self.exercise(shutdown=OSError('private'), delete_error=True, log_error=True, write_error=True)
        self.assertFalse(ok)
        for step in ('shutdown', 'bootstrap-delete', 'log-close', 'summary-write'):
            self.assertTrue(any(error.startswith(step + ':') for error in summary['cleanup_errors']))

    def test_maven_exit_zero_without_validated_reports_is_not_success(self):
        with tempfile.TemporaryDirectory() as tmp:
            summary = {'test_exit': 0}
            root = Path(tmp)
            self.assertTrue(run_mysql.cleanup_owned(None, [], {}, root/'bootstrap.sql', Mock(), root, summary))
            self.assertFalse(summary['success'])


if __name__ == '__main__':
    unittest.main()
