"""Validate Surefire reports using only the Python standard library."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

CLASS = 'com.example.smartttexam.oj.OjSubmissionTests'


def verify(directory):
    names = Path(__file__).with_name('oj-baseline.txt').read_text(encoding='utf-8-sig').splitlines()
    if len(names) != 39 or len(set(names)) != 39 or not all(names):
        raise ValueError('Invalid reviewed baseline manifest')
    required = {(CLASS, name) for name in names}
    reports = sorted(Path(directory).glob('TEST-*.xml'))
    if not reports:
        raise ValueError('Missing Surefire XML reports')
    seen = set()
    for report in reports:
        suite = ET.parse(report).getroot()
        if suite.tag != 'testsuite':
            raise ValueError(f'Unexpected XML root: {report}')
        cases = suite.findall('testcase')
        actual = {'tests': len(cases), 'failures': 0, 'errors': 0, 'skipped': 0}
        for case in cases:
            key = (case.get('classname'), case.get('name'))
            if not all(key) or key in seen:
                raise ValueError(f'Missing identity or duplicate testcase: {key}')
            seen.add(key)
            for field, node in [('failures', 'failure'), ('errors', 'error'), ('skipped', 'skipped')]:
                actual[field] += int(case.find(node) is not None)
        for field, value in actual.items():
            declared = suite.attrib[field]
            if not declared.isascii() or not declared.isdecimal() or int(declared) != value:
                raise ValueError(f'Inconsistent {field}: {report}')
        if any(actual[field] for field in ('failures', 'errors', 'skipped')):
            raise ValueError(f'Nonpassing testcase: {report}')
    if not seen or not required.issubset(seen):
        raise ValueError(f'Zero tests or missing baseline: {sorted(required - seen)}')
    return len(seen)


if __name__ == '__main__':
    try:
        if len(sys.argv) != 2:
            raise ValueError('Usage: verify_oj_reports.py REPORT_DIRECTORY')
        print(f'Verified {verify(sys.argv[1])} unique passing tests; reviewed baseline complete')
    except (ValueError, KeyError, OSError, ET.ParseError) as error:
        print(f'Surefire verification failed: {error}', file=sys.stderr)
        sys.exit(1)
