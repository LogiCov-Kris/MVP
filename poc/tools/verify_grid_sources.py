#!/usr/bin/env python3
"""Verify retained grid amounts against raw USD facts without invoking the extraction code."""
import argparse
import hashlib
import json
import re
import xml.etree.ElementTree as ET
from decimal import Decimal
from pathlib import Path


def read(path):
    return json.loads(path.read_text(), parse_float=Decimal)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--records', type=Path, default=Path('poc/out/round3'))
    parser.add_argument('--packs', type=Path, default=Path('poc/samples/packs'))
    parser.add_argument('--pack-suffix', default='')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    results, hashes = [], {}
    def tracked(path):
        hashes[str(path)] = hashlib.sha256(path.read_bytes()).hexdigest()
        return read(path)
    for ticker in ('kmi', 'wlk', 'oxy'):
        record = tracked(args.records / f'{ticker}-deal.json')
        for name, grid in [(ticker, record['grid'])] + [(p['ticker'], p['grid']) for p in record['peers']]:
            path = args.packs / f'{ticker}{args.pack_suffix}' / 'sec' / ('companyfacts.json' if name == ticker else f'peers/{name}.json')
            raw = tracked(path)['facts']['us-gaap']
            for cell in grid['values']:
                source, period = cell['source'], cell['period']
                row = {'borrower': ticker, 'entity': name, 'item': cell['item'], 'period': period,
                       'actualMillions': str(cell['value']), 'citation': source, 'components': []}
                try:
                    assert source['type'] == 'Source.Xbrl', 'Unsupported citation type'
                    parts = re.split(r' (less|plus) ', source['tag'])
                    total = Decimal(0)
                    for i in range(0, len(parts), 2):
                        tag = parts[i]
                        dimensional = re.fullmatch(r'(NetIncomeLossAvailableToCommonStockholdersBasic) \(([^)]+)\)', tag)
                        member = dimensional.group(2) if dimensional else None
                        if dimensional:
                            tag = dimensional.group(1)
                        candidates = [f for f in raw.get(tag, {}).get('units', {}).get('USD', [])
                                      if f.get('start') == period.get('start') and f['end'] == period['end']
                                      and f['form'] in ('10-K', '10-K/A', '10-Q', '10-Q/A')]
                        if member:
                            candidates = []
                        if i == 0:
                            candidates = [f for f in candidates if f['accn'] == source['accession'] and f['form'] == source['form']]
                        if not candidates and len(parts) == 1:
                            sec = args.packs / f'{ticker}{args.pack_suffix}' / 'sec'
                            manifest = tracked(sec / 'manifest.json')
                            filings = [f for f in manifest['filings'] if f['accession'] == source['accession']
                                       and f['form'] == source['form'] and f['role'].endswith('Xbrl')]
                            assert len(filings) == 1, 'Instance citation differs from manifest'
                            xml_path = sec / filings[0]['file']
                            tree = ET.parse(xml_path).getroot()
                            hashes[str(xml_path)] = hashlib.sha256(xml_path.read_bytes()).hexdigest()
                            ns = {'x': 'http://www.xbrl.org/2003/instance'}
                            contexts = {c.get('id'): c for c in tree.findall('x:context', ns)}
                            usd_units = {u.get('id') for u in tree.findall('x:unit', ns)
                                         if u.findtext('x:measure', namespaces=ns) == 'iso4217:USD'}
                            for fact in tree:
                                if fact.tag.split('}')[-1] != tag or fact.get('unitRef') not in usd_units:
                                    continue
                                context = contexts[fact.get('contextRef')]
                                dimensions = context.findall('.//{http://xbrl.org/2006/xbrldi}explicitMember')
                                if member:
                                    if len(dimensions) != 1 or dimensions[0].get('dimension').split(':')[-1] != 'StatementClassOfStockAxis' or dimensions[0].text.split(':')[-1] != member:
                                        continue
                                elif context.find('.//x:segment', ns) is not None or context.find('x:scenario', ns) is not None:
                                    continue
                                start = context.findtext('x:period/x:startDate', namespaces=ns)
                                end = context.findtext('x:period/x:endDate', namespaces=ns) or context.findtext('x:period/x:instant', namespaces=ns)
                                if start == period.get('start') and end == period['end']:
                                    candidates.append({'val': Decimal(fact.text), 'filed': '', 'accn': source['accession']})
                            row['rawInstance'] = str(xml_path)
                        assert candidates, f'No matching raw USD fact: {tag}'
                        latest = max(f['filed'] for f in candidates)
                        candidates = [f for f in candidates if f['filed'] == latest]
                        assert len(set(Decimal(f['val']) for f in candidates)) == 1, f'Conflicting raw facts: {tag}'
                        value = Decimal(candidates[0]['val'])
                        sign = -1 if i and parts[i - 1] == 'less' else 1
                        total += sign * value
                        row['components'].append({'tag': tag, 'rawUSD': str(value), 'sign': sign,
                                                  'accession': candidates[0]['accn'], 'sourceFile': row.get('rawInstance', str(path))})
                    if cell['item'] == 'InterestExpense' and parts[0] == 'InterestIncomeExpenseNonoperatingNet':
                        total = -total
                    expected = total / Decimal(1000000)
                    row['expectedMillions'] = str(expected)
                    assert expected == Decimal(cell['value']), f'Value mismatch: raw {expected}, grid {cell["value"]}'
                    row['status'] = 'verified'
                except (AssertionError, KeyError) as error:
                    row['status'] = 'unresolved'
                    row['reason'] = str(error)
                results.append(row)
    report = {'verified': sum(r['status'] == 'verified' for r in results),
              'unresolved': sum(r['status'] != 'verified' for r in results), 'values': results, 'inputSHA256': hashes}
    args.output.write_text(json.dumps(report, indent=2))
    print(f"{report['verified']} verified; {report['unresolved']} unresolved")
    for row in results:
        if row['status'] != 'verified':
            print(row['borrower'], row['entity'], row['item'], row['period']['label'], row['reason'])

    raise SystemExit(1 if report['unresolved'] else 0)

if __name__ == '__main__':
    main()
