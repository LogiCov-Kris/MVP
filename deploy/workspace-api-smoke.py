#!/usr/bin/env python3
"""Verify authenticated workspace boundaries and reference memo artifacts."""
from html.parser import HTMLParser
from urllib.parse import urlsplit, unquote
import hashlib
import http.cookiejar
import io
import json
import os
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

base = os.environ.get('LOGICOV_TEST_URL', 'http://127.0.0.1:18301')
cookies = http.cookiejar.CookieJar()
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))

def request(path, value=None, opener=client):
    headers = {'Content-Type': 'application/json', 'X-LogiCov-Request': '1'}
    req = urllib.request.Request(base + path, data=None if value is None else json.dumps(value).encode(), headers=headers)
    try:
        with opener.open(req, timeout=30) as response:
            body = response.read()
            return response.status, body
    except urllib.error.HTTPError as error:
        return error.code, error.read()

def data(path, value=None):
    status, body = request(path, value)
    assert status in (200, 202), (path, status, body[:500])
    return json.loads(body)

assert request('/api/auth/login', {'email': os.environ['LOGICOV_TEST_EMAIL'], 'password': os.environ['LOGICOV_TEST_PASSWORD']})[0] == 200
anonymous = urllib.request.build_opener()
assert request('/api/deals', opener=anonymous)[0] == 401
records = data('/api/deals')
verified = 0
for summary in records:
    if not any(t in summary['title'] for t in ('Occidental', 'Kinder Morgan', 'Westlake')):
        continue
    deal = data('/api/deals/' + summary['id'])
    route = '/api/deals/' + deal['id']
    assert request(route, opener=anonymous)[0] == 401
    assert request(route + '/files/' + deal['documents'][0]['id'], opener=anonymous)[0] == 401
    if os.environ.get('LOGICOV_EDIT_REFERENCES') == '1':
        cell = max((v for v in deal['grid']['values'] if v['item'].split('.')[-1] == 'Cash'), key=lambda v: v['period']['end'])
        original = cell['value']
        correction = dict(item=cell['item'], period=cell['period'], expected=original, value=original + 1,
                          source=cell['source'], reason='Temporary local correction verification; restore original source value immediately',
                          analyst='client-value-not-trusted', recordedAt='client-value-not-trusted')
        deal = data(route + '/corrections', {'revision': deal['revision'], 'correction': correction})
        assert deal['approval'] is None
        assert deal['events'][-1]['actor'] == os.environ['LOGICOV_TEST_EMAIL']
        correction.update(expected=original + 1, value=original, reason='Restored the verified original source value after correction test')
        deal = data(route + '/corrections', {'revision': deal['revision'], 'correction': correction})
        if deal['record']['inputs'].get('ratingReview'):
            deal = data(route + '/judgments', {'revision': deal['revision'], 'inputs': deal['record']['inputs'],
                'profile': deal['record']['profile'], 'reason': 'Verified restored source value and confirmed the existing override', 'overrideReviewed': True})
    if os.environ.get('LOGICOV_REGENERATE_REFERENCES') == '1':
        deal = data(route + '/recommendation', {'revision': deal['revision'], 'recommendation': 'CreditRecommendation.Pending', 'rationale': 'Local artifact verification; credit recommendation remains with the analyst'})
    if deal['stage'].split('.')[-1] == 'Review':
        for index in range(len(deal['issues'])):
            deal = data(route + '/issues', {'revision': deal['revision'], 'issue': index, 'reason': 'Local reference-package verification; analyst acceptance is pending'})
        deal = data(route + '/approve', {'revision': deal['revision'], 'reason': 'Local reference-package verification without institution policy; not client acceptance'})
    if deal['stage'].split('.')[-1] in ('Approved', 'Failed'):
        deal = data(route + '/resume', {'revision': deal['revision'], 'reason': ''})
    deadline = time.monotonic() + 180
    while deal['stage'].split('.')[-1] == 'Generating' and time.monotonic() < deadline:
        time.sleep(1)
        deal = data(route)
    assert deal['stage'].split('.')[-1] == 'Completed', (deal['title'], deal.get('failure'))
    bundle = next(d for d in reversed(deal['documents']) if d['contentType'] == 'application/zip')
    status, payload = request(route + '/files/' + bundle['id'])
    assert status == 200 and hashlib.sha256(payload).hexdigest() == bundle['sha256']
    archive = zipfile.ZipFile(io.BytesIO(payload))
    assert archive.testzip() is None
    hashes = json.loads(archive.read('SHA256SUMS.json'))
    for name, digest in hashes.items():
        assert hashlib.sha256(archive.read(name)).hexdigest() == digest, name
    assert archive.read('memo.pdf').startswith(b'%PDF-')
    class Links(HTMLParser):
        def __init__(self):
            super().__init__()
            self.links, self.ids = [], set()
        def handle_starttag(self, tag, attrs):
            attrs = dict(attrs)
            if 'id' in attrs:
                self.ids.add(attrs['id'])
            if tag == 'a' and 'href' in attrs:
                self.links.append(attrs['href'])
    pages = {}
    for name in ('memo.html', 'audit.html'):
        parser = Links()
        parser.feed(archive.read(name).decode())
        pages[name] = parser
    for name, parser in pages.items():
        for href in parser.links:
            link = urlsplit(href)
            if link.scheme:
                continue
            target = unquote(link.path) or name
            assert target in archive.namelist(), (name, href)
            if link.fragment and target in pages:
                assert unquote(link.fragment) in pages[target].ids, (name, href)
    approved = json.loads(archive.read('approved-workspace.json'))
    assert approved['approval'] == deal['approval']
    assert len([n for n in archive.namelist() if n.startswith('source-')]) == len([d for d in deal['documents'] if not d['name'].startswith('generated-')])
    verified += 1
    out = Path(os.environ.get('LOGICOV_TEST_OUTPUT', 'poc/out/workspace-verification'))
    out.mkdir(parents=True, exist_ok=True)
    (out / (deal['record']['ticker'].lower() + '-review.zip')).write_bytes(payload)
    print(deal['title'] + ': approved snapshot, cited PDF, source bundle and hashes verified')
assert verified == 3, f'Expected three reference borrowers, verified {verified}'
print('Workspace API and reference artifact verification passed')
