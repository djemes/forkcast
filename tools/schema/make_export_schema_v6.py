"""Builds docs/export-schema-v6.json from v5 (1.2: meal times, Describe, estimate status).

Run from the repository root: python -X utf8 tools/schema/make_export_schema_v6.py
BackupRepositoryTest.schemaFileMatchesExport checks the result against a real export.
"""
import json

src = json.load(open('docs/export-schema-v5.json', encoding='utf8'))
s = json.loads(json.dumps(src))
d = s['$defs']

s['$id'] = s['$id'].replace('v5', 'v6')
s['title'] = 'Forkcast export, schema version 6'
s['properties']['schemaVersion']['enum'] = [1, 2, 3, 4, 5, 6]
s['description'] += (
    ' v6 (1.2): log entries carry when they were eaten (eatenMinute, minutes after local midnight on their date,'
    ' and timeEstimated for entries from before meal times; older files get a typical time per meal on import),'
    ' foods saved from Describe carry describedAs, the AI_TEXT source marks described meals,'
    ' and AI estimates carry their status and retry fields.'
)

d['foodSource']['enum'] = d['foodSource']['enum'] + ['AI_TEXT']

d['food']['properties']['describedAs'] = {
    '$ref': '#/$defs/optionalString',
    'description': 'v6: what the user typed when this food was saved from Describe (AI_TEXT)',
}

d['logEntry']['properties']['eatenMinute'] = {
    'type': ['integer', 'null'], 'minimum': 0, 'maximum': 1439,
    'description': 'v6: when it was eaten, minutes after local midnight on date',
}
d['logEntry']['properties']['timeEstimated'] = {
    'type': 'boolean',
    'description': 'v6: eatenMinute is a typical time for the meal (entries from before meal times), not one the user logged or chose',
}

attempt = {
    'type': 'object',
    'description': 'One request to one profile: when, how long, what came back. Never the request or the key.',
    'required': ['atMs', 'profileId', 'profileName', 'model', 'durationMs', 'result'],
    'properties': {
        'atMs': {'$ref': '#/$defs/epochMs'},
        'profileId': {'type': 'string'},
        'profileName': {'type': 'string'},
        'model': {'type': 'string'},
        'durationMs': {'type': 'integer'},
        'result': {'type': 'string', 'description': 'OK, or Timeout, RateLimited, QuotaExhausted, Server, Network, InvalidKey, ModelNotFound, ContentRefused, BadResponse, InvalidRequest'},
        'httpStatus': {'type': ['integer', 'null']},
        'providerCode': {'$ref': '#/$defs/optionalString'},
        'quota': {'$ref': '#/$defs/optionalString'},
        'message': {'$ref': '#/$defs/optionalString'},
        'retryAfterMs': {'type': ['integer', 'null']},
    },
}
d['attemptRecord'] = attempt
p = d['aiEstimate']['properties']
p['kind'] = {'enum': ['PHOTO', 'TEXT'], 'description': 'v6: TEXT = Describe (no photo)'}
p['portion'] = {'type': 'number', 'description': 'v6: share of the estimated plate that was eaten (1 = all)'}
p['attemptLog'] = {'type': 'array', 'items': {'$ref': '#/$defs/attemptRecord'}, 'description': 'v6: newest 30 requests'}
p['tries'] = {'type': 'integer', 'description': 'v6: passes through the profile chain since the last user action'}
p['runStartedAt'] = {'$ref': '#/$defs/optionalEpochMs'}
p['eatenMinute'] = {'type': ['integer', 'null'], 'description': 'v6: meal time chosen when logging'}
p['phase'] = {'oneOf': [{'enum': ['SENDING', 'WAITING', 'MATCHING']}, {'type': 'null'}]}
p['activeProfileName'] = {'$ref': '#/$defs/optionalString'}
p['phaseSince'] = {'$ref': '#/$defs/optionalEpochMs'}
p['onlyProfileId'] = {'$ref': '#/$defs/optionalString'}
p['ignoreCooldown'] = {'type': 'boolean'}

st = d['settings']['properties']
st['waterContainers'] = {
    'type': 'object',
    'description': 'v6: Today\'s water containers in ml (null = default): glassMl, canMl, bottleMl, tumblerMl; lastUsed (GLASS, CAN, BOTTLE, TUMBLER)',
}
st['backup'] = st.get('backup', {'type': 'object'})
st['backup']['description'] = 'Google Drive backup: auto, frequency (DAILY, WEEKLY), weekday (1 = Monday), wifiOnly, chargingOnly'

json.dump(s, open('docs/export-schema-v6.json', 'w', encoding='utf8', newline='\n'), indent=2, ensure_ascii=False)
open('docs/export-schema-v6.json', 'a', encoding='utf8', newline='\n').write('\n')
print('docs/export-schema-v6.json written')
