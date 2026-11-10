import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET
sys.dont_write_bytecode = True
from verify_oj_reports import CLASS, load_baseline

SCRIPT = Path(__file__).with_name('verify_oj_reports.py')
NAMES = load_baseline()


def report(names):
    root = ET.Element('testsuite', tests=str(len(names)), failures='0', errors='0', skipped='0')
    for classname, name in names:
        ET.SubElement(root, 'testcase', classname=classname, name=name)
    return root


class ReportTests(unittest.TestCase):
    def test_report_scenarios(self):
        scenarios = ['valid', 'duplicate', 'failure', 'error', 'skipped', 'count',
                     'cross_duplicate', 'missing_baseline', 'missing', 'damaged', 'zero', 'extra', 'split']
        for scenario in scenarios:
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory() as directory:
                root = report(NAMES)
                if scenario == 'duplicate': root = report([NAMES[0]] * len(NAMES))
                if scenario in ('failure', 'error', 'skipped'): ET.SubElement(root[0], scenario)
                if scenario == 'count': root.set('tests', str(len(NAMES) + 1))
                if scenario == 'missing_baseline': root = report(NAMES[:-1] + [(CLASS, 'replacement')])
                if scenario == 'zero': root = report([])
                if scenario == 'extra': root = report(NAMES + [(CLASS, 'new_test')])
                if scenario == 'split': root = report(NAMES[:20])
                path = Path(directory) / 'TEST-first.xml'
                if scenario != 'missing': ET.ElementTree(root).write(path)
                if scenario == 'damaged': path.write_text('broken')
                if scenario == 'cross_duplicate': ET.ElementTree(report([NAMES[0]])).write(Path(directory) / 'TEST-second.xml')
                if scenario == 'split': ET.ElementTree(report(NAMES[20:])).write(Path(directory) / 'TEST-second.xml')
                result = subprocess.run([sys.executable, str(SCRIPT), directory], capture_output=True, text=True)
                expected = 0 if scenario in ('valid', 'extra', 'split') else 1
                self.assertEqual(expected, result.returncode, result.stderr)
                print(f'{scenario}: exit={result.returncode}, expected={expected}')

    def test_each_new_auth_and_backend_baseline_is_required(self):
        for missing in NAMES[53:]:
            with self.subTest(missing=missing), tempfile.TemporaryDirectory() as directory:
                ET.ElementTree(report([name for name in NAMES if name != missing])).write(Path(directory) / 'TEST-missing.xml')
                result = subprocess.run([sys.executable, str(SCRIPT), directory], capture_output=True, text=True)
                self.assertEqual(1, result.returncode, result.stdout)
                self.assertIn('missing baseline', result.stderr)
        print(f'{len(NAMES[53:])} added baseline removals: all correctly rejected')


if __name__ == '__main__':
    unittest.main()
