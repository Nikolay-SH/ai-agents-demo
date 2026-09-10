#!/usr/bin/env python3
"""Explicit integration test: breaks and restarts only the local sherlock-live stand.
Approval input is automated TEST input, never represented as a human decision.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import threading
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

def invoke(mode, approve, label):
    args = ['live', 'restart', 'payment'] if mode == 'operator' else [mode, 'LIVE']
    capture = ROOT / 'captures' / f'live-{label}.txt'
    capture.parent.mkdir(exist_ok=True)
    process = subprocess.Popen([str(ROOT / 'scripts/demo'), *args], cwd=ROOT,
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, bufsize=1, env={**os.environ, 'APPROVAL_TIMEOUT_SECONDS': '20'})
    timer = threading.Timer(260, process.kill)
    timer.start()
    asked = False
    service = None
    tokens = set()
    lines = []
    try:
        with capture.open('w') as output:
            output.write('AUTOMATED INTEGRATION TEST INPUT, not a human-operated recording\n')
            for line in process.stdout:
                output.write(line); output.flush(); lines.append(line)
                print(line, end='', flush=True)
                if line.startswith('Service: '): service = line.strip().split(': ', 1)[1]
                match = re.match(r'Type exactly: approve ([0-9a-f-]{36})', line)
                if match and match[1] not in tokens:
                    asked = True
                    tokens.add(match[1])
                    process.stdin.write('approve ' + match[1] + '\n' if approve and service == 'payment' else '\n')
                    process.stdin.flush()
            assert process.wait() == 0, f'Process failed; see {capture}'
    finally:
        timer.cancel()
        if process.poll() is None: process.kill(); process.wait()
    assert asked, f'Agent did not request approval; see {capture}'
    expected = 'EXECUTED' if approve else 'REJECTED'
    assert expected in ''.join(lines), f'{expected} missing; see {capture}'
    if mode != 'operator' and approve:
        after_restart = ''.join(lines).split('EXECUTED:', 1)[1]
        assert re.search(r'\[E\d+\] getServiceInfo\(', after_restart), 'Agent did not recheck service after restart'
        assert re.search(r'\[E\d+\] getMetrics\(', after_restart), 'Agent did not recheck metrics after restart'
    return capture

if __name__ == '__main__':
    mode = sys.argv[1] if len(sys.argv) > 1 else 'operator'
    assert mode in ('operator', 'spring', 'koog'), 'Usage: check-live.py [operator|spring|koog]'
    ready()
    before = boot()
    assert http('/demo/faults/close-pool', 18082, 'POST')[0] == 200
    assert http('/checkout', method='POST')[0] == 503
    invoke(mode, False, mode + '-rejected')
    assert boot() == before, 'Rejection changed the process'
    assert http('/checkout', method='POST')[0] == 503, 'Fault unexpectedly disappeared'
    invoke(mode, True, mode + '-approved')
    ready()
    assert boot() != before, 'Approval did not produce a new process'
    assert http('/checkout', method='POST')[0] == 200
    print(f'PASS {mode}: healthy → real 503 → rejected/no restart → approved/new bootId → HTTP 200')
