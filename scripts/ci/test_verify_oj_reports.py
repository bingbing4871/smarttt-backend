import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET
sys.dont_write_bytecode = True
from verify_oj_reports import CLASS

SCRIPT = Path(__file__).with_name('verify_oj_reports.py')
NAMES = SCRIPT.with_name('oj-baseline.txt').read_text(encoding='utf-8-sig').splitlines()


def report(names):
    root = ET.Element('testsuite', tests=str(len(names)), failures='0', errors='0', skipped='0')
    for name in names:
        ET.SubElement(root, 'testcase', classname=CLASS, name=name)
    return root


class ReportTests(unittest.TestCase):
    def test_report_scenarios(self):
        scenarios = ['valid', 'duplicate', 'failure', 'error', 'skipped', 'count',
                     'cross_duplicate', 'missing_baseline', 'missing', 'damaged', 'zero', 'extra', 'split']
        for scenario in scenarios:
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory() as directory:
                root = report(NAMES)
                if scenario == 'duplicate': root = report([NAMES[0]] * 39)
                if scenario in ('failure', 'error', 'skipped'): ET.SubElement(root[0], scenario)
                if scenario == 'count': root.set('tests', '40')
                if scenario == 'missing_baseline': root = report(NAMES[:-1] + ['replacement'])
                if scenario == 'zero': root = report([])
                if scenario == 'extra': root = report(NAMES + ['new_test'])
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


if __name__ == '__main__':
    unittest.main()
