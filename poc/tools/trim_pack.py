"""Trim a fetched source pack into a small test fixture that keeps only what DealBuilder reads."""
import json, os, re, shutil, sys
import xml.etree.ElementTree as ET

src, dst, scala_root = sys.argv[1], sys.argv[2], sys.argv[3]
X = '{http://www.xbrl.org/2003/instance}'; XDI = '{http://xbrl.org/2006/xbrldi}'
L = '{http://www.xbrl.org/2003/linkbase}'; XL = '{http://www.w3.org/1999/xlink}'

def scala_strings(*files):
    out = set()
    for f in files:
        out |= set(re.findall(r'"([A-Z][A-Za-z]+)"', open(os.path.join(scala_root, f)).read()))
    return out

line_item_tags = set(re.findall(r'"([A-Z][A-Za-z]+)"', open(os.path.join(scala_root, 'core/src/main/scala/logicov/grid/LineItem.scala')).read()))
deal = json.load(open(os.path.join(src, 'deal.json')))
spread = deal['ebitda']['spread']
keep_concepts = scala_strings('server/src/main/scala/logicov/edgar/SegmentExtractor.scala', 'server/src/main/scala/logicov/edgar/DebtSchedule.scala',
                              'server/src/main/scala/logicov/deal/DealBuilder.scala')
keep_concepts |= {'NetIncomeLossAvailableToCommonStockholdersBasic', 'DocumentPeriodEndDate', 'DebtInstrumentInterestRateStatedPercentage', 'LineOfCreditFacilityMaximumBorrowingCapacity',
                  'LettersOfCreditOutstandingAmount', 'LineOfCreditFacilityRemainingBorrowingCapacity', 'DebtInstrumentCarryingAmount'}
if spread: keep_concepts |= {spread['base'], *spread['addBacks']}

def register(path):
    for _, (prefix, uri) in ET.iterparse(path, events=['start-ns']):
        ET.register_namespace(prefix, uri)

def trim_instance(path, out):
    register(path)
    tree = ET.parse(path); root = tree.getroot()
    facts = [e for e in list(root) if 'contextRef' in e.attrib and e.tag.split('}')[1] in keep_concepts]
    ctx_ids = {e.get('contextRef') for e in facts}; unit_ids = {e.get('unitRef') for e in facts if e.get('unitRef')}
    members = set()
    for e in list(root):
        tag = e.tag.split('}')[1]
        if 'contextRef' in e.attrib: keep = e in facts
        elif tag == 'context': keep = e.get('id') in ctx_ids
        elif tag == 'unit': keep = e.get('id') in unit_ids
        else: keep = tag in ('schemaRef',)
        if not keep: root.remove(e)
        elif tag == 'context':
            members |= {m.text.strip().split(':')[1] for m in e.iter(XDI + 'explicitMember')}
    tree.write(out, xml_declaration=True, encoding='utf-8')
    return members

def trim_peer(path, out):
    register(path)
    tree = ET.parse(path); root = tree.getroot()
    facts = [e for e in list(root) if 'contextRef' in e.attrib and 'CapitalExpenditures' in e.tag.split('}')[1]]
    ctx_ids = {e.get('contextRef') for e in facts}; unit_ids = {e.get('unitRef') for e in facts if e.get('unitRef')}
    for e in list(root):
        tag = e.tag.split('}')[1]
        keep = (e in facts) if 'contextRef' in e.attrib else (e.get('id') in ctx_ids if tag == 'context' else (e.get('id') in unit_ids if tag == 'unit' else tag == 'schemaRef'))
        if not keep: root.remove(e)
    tree.write(out, xml_declaration=True, encoding='utf-8')

def trim_labels(path, out, members):
    register(path)
    tree = ET.parse(path); root = tree.getroot()
    for link in root.iter(L + 'labelLink'):
        locs = {e.get(XL + 'label'): e.get(XL + 'href').split('#')[1].split('_', 1)[1] for e in link.iter(L + 'loc')}
        arcs = [a for a in link.iter(L + 'labelArc') if locs.get(a.get(XL + 'from')) in members]
        keep_locs = {a.get(XL + 'from') for a in arcs}; keep_labels = {a.get(XL + 'to') for a in arcs}
        for e in list(link):
            tag = e.tag.split('}')[1]
            keep = (tag == 'loc' and e.get(XL + 'label') in keep_locs) or (tag == 'label' and e.get(XL + 'label') in keep_labels) or (tag == 'labelArc' and e in arcs)
            if not keep: link.remove(e)
    tree.write(out, xml_declaration=True, encoding='utf-8')

def trim_companyfacts(path, out, since):
    d = json.load(open(path))
    facts = {k: {'units': {'USD': [f for f in v['units']['USD'] if f['end'] >= since]}}
             for k, v in d['facts']['us-gaap'].items() if k in line_item_tags and 'USD' in v['units']}
    json.dump({'cik': d['cik'], 'entityName': d['entityName'], 'facts': {'us-gaap': facts}}, open(out, 'w'))

def excerpt(html, pattern, out):
    text = re.sub(r'\s+', ' ', re.sub(r'<[^>]+>', ' ', html))
    found = [m.group(0) for m in re.finditer(r'[^.]{0,400}' + pattern + r'[^.]{0,300}\.', text, re.I)]
    open(out, 'w').write('<html><body>' + ''.join(f'<p>{f.strip()}</p>' for f in found) + '</body></html>')

if os.path.exists(dst): shutil.rmtree(dst)
os.makedirs(os.path.join(dst, 'sec', 'peers'))
for f in ('deal.json', 'call-script.md'): shutil.copy(os.path.join(src, f), os.path.join(dst, f))
sec = os.path.join(src, 'sec'); manifest = json.load(open(os.path.join(sec, 'manifest.json')))
manifest['filings'] = [f for f in manifest['filings'] if f['role'] not in ('PeerAnnualReport',)]
json.dump(manifest, open(os.path.join(dst, 'sec', 'manifest.json'), 'w'), indent=2)
if os.path.exists(os.path.join(sec, 'officers.json')): shutil.copy(os.path.join(sec, 'officers.json'), os.path.join(dst, 'sec', 'officers.json'))
members = {}
for f in manifest['filings']:
    i, o = os.path.join(sec, f['file']), os.path.join(dst, 'sec', f['file'])
    if f['role'] in ('AnnualXbrl', 'InterimXbrl'): members[f['role']] = trim_instance(i, o)
    elif f['role'] == 'PeerAnnualXbrl':
        os.makedirs(os.path.dirname(o), exist_ok=True)
        trim_peer(i, o)
for f in manifest['filings']:
    i, o = os.path.join(sec, f['file']), os.path.join(dst, 'sec', f['file'])
    if f['role'] == 'AnnualLabels': trim_labels(i, o, members['AnnualXbrl'])
    elif f['role'] == 'InterimLabels': trim_labels(i, o, members['InterimXbrl'])
    elif f['role'] == 'PeerRatingsOffering': shutil.copy(i, o)
    elif f['role'] == 'Proxy':
        import html
        text = html.unescape(re.sub(r'\s+', ' ', re.sub(r'<[^>]+>', ' ', open(i, encoding='utf-8').read())))
        passages = [text[max(0, m.start()-100):m.end()+2000] for m in re.finditer(r'Director since \d{4}', text)]
        open(o, 'w').write('<html><body>' + ''.join('<p>' + html.escape(t) + '</p>' for t in passages) + '</body></html>')
    elif f['role'] == 'AnnualReport': excerpt(open(i, encoding='utf-8', errors='replace').read(), r'contract life', o)
    elif f['role'] == 'EarningsRelease':
        html = open(i, encoding='utf-8', errors='replace').read()
        tables = [t for t in re.findall(r'(?is)<table.*?</table>', html) if 'EBITDA' in t]
        open(o, 'w').write('<html><body>' + ''.join(tables) + '</body></html>')
trim_companyfacts(os.path.join(sec, 'companyfacts.json'), os.path.join(dst, 'sec', 'companyfacts.json'), '2022-12-31')
for peer in deal['peers']:
    r = os.path.join(sec, 'peers', f'{peer["ticker"].upper()}-ratings.json')
    if os.path.exists(r): shutil.copy(r, os.path.join(dst, 'sec', 'peers', os.path.basename(r)))
for p in [f'{peer["ticker"].upper()}.json' for peer in deal['peers']]:
    trim_companyfacts(os.path.join(sec, 'peers', p), os.path.join(dst, 'sec', 'peers', p), '2024-12-31')
