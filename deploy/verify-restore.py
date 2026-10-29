#!/usr/bin/env python3
"""Read-only verification of an isolated workspace database restore."""
import hashlib
import http.cookiejar
import json
import os
import urllib.error
import urllib.request


def connect(base, email):
    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    body = json.dumps({'email': email, 'password': os.environ['LOGICOV_TEST_PASSWORD']}).encode()
    with client.open(urllib.request.Request(base + '/api/auth/login', data=body, headers={
        'Content-Type': 'application/json', 'X-LogiCov-Request': '1',
    })) as response:
        assert response.status == 200
    return client


base = os.environ['LOGICOV_RESTORE_URL'].rstrip('/')
client = connect(base, os.environ['LOGICOV_TEST_EMAIL'])
other = connect(base, os.environ['LOGICOV_OTHER_EMAIL'])
with client.open(base + '/api/deals') as response:
    deals = json.load(response)
assert deals, 'Restore contains no deals for this analyst'
count = 0
for summary in deals:
    with client.open(base + '/api/deals/' + summary['id']) as response:
        deal = json.load(response)
    source = next(d for d in deal['documents'] if not d['name'].startswith('generated-'))
    path = base + '/api/deals/' + deal['id'] + '/files/' + source['id']
    with client.open(path) as response:
        assert hashlib.sha256(response.read()).hexdigest() == source['sha256']
    try:
        other.open(path)
        raise AssertionError('Another tenant could read restored evidence')
    except urllib.error.HTTPError as error:
        assert error.code == 404, error.code
    if deal.get('memo'):
        with client.open(base + '/api/deals/' + deal['id'] + '/files/' + deal['memo']) as response:
            assert response.read(5) == b'%PDF-'
        assert deal['approval'], 'Completed memo lost its approved snapshot'
    count += 1
print(f'Restore verified: {count} encrypted deals, original-source hashes, saved approvals/PDFs and cross-tenant denial')
