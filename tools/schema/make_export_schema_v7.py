"""Builds docs/export-schema-v7.json from v6 (1.4: log entries lose timeEstimated).

Run from the repository root: python -X utf8 tools/schema/make_export_schema_v7.py
BackupRepositoryTest.schemaFileMatchesExport checks the result against a real export.
The next format change copies this script: read the current file, apply the change, write the next.
"""
import json

s = json.load(open('docs/export-schema-v6.json', encoding='utf8'))
d = s['$defs']

s['$id'] = s['$id'].replace('v6', 'v7')
s['title'] = 'Forkcast export, schema version 7'
# Versions this one imports: its own and the one before it (owner, 2026-10-24).
s['properties']['schemaVersion']['enum'] = [6, 7]
s['description'] = (
    'Full data export (SPEC §4.11, §6.3). Field names mirror the Room entities. Times are epoch milliseconds UTC;'
    ' dates are ISO yyyy-MM-dd local dates; amounts are grams, kcal, mg, µg, ml, kg. Rows with a non-null deletedAt'
    ' are soft-deleted and are kept so merges respect deletions. Secrets are never included. Readers must ignore'
    ' unknown fields. Health Connect write state is per phone and never exported. Kept in sync with the code by'
    ' BackupRepositoryTest.schemaFileMatchesExport. v7 (1.4): log entries no longer carry timeEstimated; every'
    ' eatenMinute is a normal time. Version 1.4 also imports v6 files (1.2, 1.3), whose timeEstimated is ignored.'
)
del d['logEntry']['properties']['timeEstimated']
req = d['logEntry'].get('required')
if req and 'timeEstimated' in req:
    req.remove('timeEstimated')

json.dump(s, open('docs/export-schema-v7.json', 'w', encoding='utf8', newline='\n'), indent=2, ensure_ascii=False)
open('docs/export-schema-v7.json', 'a', encoding='utf8', newline='\n').write('\n')
