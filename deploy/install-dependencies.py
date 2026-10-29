#!/usr/bin/env python3
"""Install a checksum-pinned binary dependency bundle into an Ivy repository."""
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import sys
import tarfile

archive, expected, destination = sys.argv[1:]
data = Path(archive).read_bytes()
if hashlib.sha256(data).hexdigest() != expected:
    raise SystemExit('Dependency bundle checksum does not match the reviewed version')
root = Path(destination).resolve()
with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as tar:
    manifest = json.load(tar.extractfile('SHA256SUMS.json'))
    entries = tar.getmembers()
    if len({e.name for e in entries}) != len(entries):
        raise SystemExit('Duplicate archive paths')
    validated = []
    for member in entries:
        if member.name == 'SHA256SUMS.json':
            continue
        path = PurePosixPath(member.name)
        if not member.isfile() or path.is_absolute() or '..' in path.parts or path.parts[0] != 'local':
            raise SystemExit('Unsafe dependency archive path')
        payload = tar.extractfile(member).read()
        key = str(PurePosixPath(*path.parts[1:]))
        if hashlib.sha256(payload).hexdigest() != manifest.get(key):
            raise SystemExit('Dependency payload checksum does not match')
        validated.append((root.joinpath(*path.parts), payload))
    if len(validated) != len(manifest):
        raise SystemExit('Dependency manifest coverage mismatch')
    for file, payload in validated:
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_bytes(payload)
print(f'Installed {len(validated)} verified dependency files')
