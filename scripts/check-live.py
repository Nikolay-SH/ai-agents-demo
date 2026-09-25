#!/usr/bin/env python3
"""Explicit integration test of the approval boundary: breaks and restarts only the local sherlock-live stand.
Runs the operator harness, not an agent. Approval input is automated TEST input, never a human decision.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.request
import urllib.error

ROOT = Path(__file__).resolve().parent.parent

def http(path, port=18081, method='GET'):
    try:
        with urllib.request.urlopen(urllib.request.Request(f'http://127.0.0.1:{port}{path}', method=method), timeout=5) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()

def boot():
    return json.loads(http('/demo/status', 18082)[1])['bootId']

def ready():
    end = time.monotonic() + 45
    while time.monotonic() < end:
        try:
            if http('/checkout', method='POST')[0] == 200:
                return
        except (OSError, TimeoutError):
            pass
        time.sleep(.5)
    raise AssertionError('checkout did not recover within 45 seconds')

def restart(approve, label):
    capture = ROOT / 'captures' / f'live-{label}.txt'
    capture.parent.mkdir(exist_ok=True)
    process = subprocess.Popen([str(ROOT / 'scripts/demo'), 'live', 'restart', 'payment'], cwd=ROOT,
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, bufsize=1, env={**os.environ, 'APPROVAL_TIMEOUT_SECONDS': '20'})
    asked, lines = False, []
    with capture.open('w') as output:
        output.write('AUTOMATED INTEGRATION TEST INPUT, not a human-operated recording\n')
        for line in process.stdout:
            output.write(line); lines.append(line); print(line, end='', flush=True)
            match = re.match(r'Type exactly: approve (\S+)', line)
            if match:
                asked = True
                process.stdin.write(('approve ' + match[1] if approve else '') + '\n')
                process.stdin.flush()
    assert process.wait() == 0, f'Process failed; see {capture}'
    assert asked, f'No approval was requested; see {capture}'
    expected = 'EXECUTED' if approve else 'REJECTED'
    assert expected in ''.join(lines), f'{expected} missing; see {capture}'

if __name__ == '__main__':
    ready()
    before = boot()
    assert http('/demo/faults/close-pool', 18082, 'POST')[0] == 200
    assert http('/checkout', method='POST')[0] == 503
    restart(False, 'operator-rejected')
    assert boot() == before, 'Rejection changed the process'
    assert http('/checkout', method='POST')[0] == 503, 'Fault unexpectedly disappeared'
    restart(True, 'operator-approved')
    ready()
    assert boot() != before, 'Approval did not produce a new process'
    print('PASS: healthy → real 503 → rejected/no restart → approved/new bootId → HTTP 200')
