#!/usr/bin/env python3
"""Export the local binary dependencies needed to reproduce this checkout in CI."""
import hashlib
import io
import json
import os
from pathlib import Path
import re
import tarfile
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent.parent
local = Path(os.environ.get('LOGICOV_IVY_LOCAL', str(Path.home() / '.ivy2' / 'local')))
output = root / 'target' / 'build-dependencies'
output.mkdir(parents=True, exist_ok=True)
build = (root / 'build.sbt').read_text()
versions = dict(re.findall(r'val (\w+Version): String = "([^"]+)"', build))
seeds = set()
for group, name, version in re.findall(r'"([^"]+)"\s+%%?\s+"([^"]+)"\s+%\s+(\w+Version)', build):
    for artifact in [name, name + '_3', name + '_sjs1_3']:
        coordinate = (group, artifact, versions[version])
        if (local.joinpath(*coordinate) / 'ivys' / 'ivy.xml').exists():
            seeds.add(coordinate)
for group, name, version in re.findall(r'"([^"]+)"\s+%\s+"([^"]+)"\s+%\s+"([^"]+)"', (root / 'project' / 'plugins.sbt').read_text()):
    for artifact in [name, name + '_sbt2_3']:
        coordinate = (group, artifact, version)
        if (local.joinpath(*coordinate) / 'ivys' / 'ivy.xml').exists():
            seeds.add(coordinate)

pending = list(seeds)
seen = set()
files = {}
while pending:
    coordinate = pending.pop()
    if coordinate in seen:
        continue
    seen.add(coordinate)
    directory = local.joinpath(*coordinate)
    ivy = directory / 'ivys' / 'ivy.xml'
    document = ET.parse(ivy)
    for dep in list(document.findall('./dependencies/dependency')):
        configurations = {part.split('->')[0] for part in dep.attrib.get('conf', '').split(';')}
        if configurations and configurations.issubset({'test', 'docs', 'sources', 'scala-tool', 'scala-doc-tool', 'scala-repl-tool', 'zinc-tool'}):
            document.find('dependencies').remove(dep)
            continue
        child = (dep.attrib['org'], dep.attrib['name'], dep.attrib['rev'])
        if (local.joinpath(*child) / 'ivys' / 'ivy.xml').exists():
            pending.append(child)
    binaries = [file for file in (directory / 'jars').glob('*.jar') if not file.name.endswith(('-tests.jar', '-sources.jar', '-javadoc.jar'))]
    if not binaries:
        raise SystemExit(f'No binaries found for {coordinate}')
    publications = document.find('publications')
    for artifact in list(publications):
        if artifact.attrib.get('type') != 'jar' or any(k.endswith('classifier') for k in artifact.attrib):
            publications.remove(artifact)
    key = '/'.join(coordinate)
    files[f'{key}/ivys/ivy.xml'] = ET.tostring(document.getroot(), encoding='utf-8', xml_declaration=True)
    for binary in binaries:
        files[f'{key}/jars/{binary.name}'] = binary.read_bytes()

manifest = {name: hashlib.sha256(data).hexdigest() for name, data in sorted(files.items())}
archive = output / 'logicov-build-dependencies.tar.gz'
with tarfile.open(archive, 'w:gz') as tar:
    for name, data in sorted(files.items()):
        info = tarfile.TarInfo('local/' + name)
        info.size = len(data)
        info.mode = 0o644
        tar.addfile(info, io.BytesIO(data))
    data = (json.dumps(manifest, indent=2) + '\n').encode()
    info = tarfile.TarInfo('SHA256SUMS.json')
    info.size = len(data)
    tar.addfile(info, io.BytesIO(data))
digest = hashlib.sha256(archive.read_bytes()).hexdigest()
(archive.parent / 'SHA256SUM').write_text(digest + '\n')
print(f'{len(seen)} binary modules, {len(files)} files: {archive.relative_to(root)}')
print('SHA256:', digest)
