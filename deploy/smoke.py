#!/usr/bin/env python3
"""Exercise a running local application with a pre-provisioned test account."""
import base64
import http.cookiejar
import json
import os
import urllib.error
import urllib.request

base = os.environ.get("LOGICOV_TEST_URL", "http://127.0.0.1:8300").rstrip("/")
jar = http.cookiejar.CookieJar()
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))


def request(path, body=None, csrf=True):
    headers = {"Content-Type": "application/json"}
    if csrf:
        headers["X-LogiCov-Request"] = "1"
    req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
    try:
        response = client.open(req, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    return response.status, response.read(), response.headers


assert request("/api/health")[0] == 200
assert request("/api/ready")[0] == 200
status, html, _ = request("/")
assert status == 200 and b"LogiCov" in html and b"main" in html
assert request("/api/session")[0] == 401
assert request("/api/import", {})[0] == 401
login = {"email": os.environ["LOGICOV_TEST_EMAIL"], "password": os.environ["LOGICOV_TEST_PASSWORD"]}
assert request("/api/auth/login", login, csrf=False)[0] == 403
assert request("/api/auth/login", dict(login, password="incorrect"))[0] == 401
status, _, headers = request("/api/auth/login", login)
assert status == 200
cookie = headers["Set-Cookie"]
assert "HttpOnly" in cookie and "SameSite=Strict" in cookie
status, body, headers = request("/api/session")
assert status == 200 and headers["Cache-Control"] == "no-store"
assert json.loads(body)["email"] == login["email"].lower()
payload = {"filename": "financials.csv", "data": base64.b64encode(b"Revenue,1200\nCash,50").decode(),
           "options": {"entity": "HTTP Example", "unit": "Millions", "columns": [
               {"column": 2, "label": "FY2025", "start": "2025-01-01", "end": "2025-12-31"}]}}
assert request("/api/import", payload, csrf=False)[0] == 403
status, body, headers = request("/api/import", payload)
assert status == 200 and headers["Cache-Control"] == "no-store"
result = json.loads(body)
assert len(result["grid"]["values"]) == 2 and not result["issues"]
assert result["grid"]["values"][0]["source"]["location"] == "record 1, column 2"
assert request("/api/import", dict(payload, filename="../financials.csv"))[0] == 400
assert request("/api/import", dict(payload, data="not-base64!"))[0] == 400
assert request("/api/auth/logout", {}, csrf=False)[0] == 403
assert request("/api/session")[0] == 200
assert request("/api/auth/logout", {})[0] == 200
assert request("/api/session")[0] == 401
assert request("/%2e%2e/build.sbt")[0] == 404
assert request("/api/unknown")[0] == 404
print("HTTP smoke passed: assets, readiness, authentication, session, CSRF, upload, validation, logout and path boundary")
