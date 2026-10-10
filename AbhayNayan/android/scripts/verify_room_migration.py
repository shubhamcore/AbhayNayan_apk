#!/usr/bin/env python3
"""Verify the Room 1 -> 2 auto-migration against real SQLite without a device.

Builds a v1 database from the exported schema (app/schemas/.../1.json), inserts a v1 screening,
executes the SQL that Room's KSP processor generated (AbhayDatabase_AutoMigration_1_2_Impl.kt),
then checks that every table matches 2.json and that the v1 row survived with correct defaults.

Run after a build:  python scripts/verify_room_migration.py
"""
from __future__ import annotations

import json
import re
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCHEMAS = ROOT / 'app' / 'schemas' / 'in.abhaynayan.app.data.AbhayDatabase'
GENERATED = ROOT / 'app' / 'build' / 'generated' / 'ksp' / 'debug' / 'kotlin' / 'in' / 'abhaynayan' / 'app' / 'data' / 'AbhayDatabase_AutoMigration_1_2_Impl.kt'


def create_statements(schema: dict) -> list[str]:
    db = schema['database']
    out = []
    for entity in db['entities']:
        out.append(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        for index in entity.get('indices', []):
            out.append(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))
    return out


def columns(conn: sqlite3.Connection, table: str) -> dict[str, tuple]:
    return {row[1]: (row[2].upper(), bool(row[3]), row[4], row[5]) for row in conn.execute(f'PRAGMA table_info(`{table}`)')}


def main() -> int:
    v1 = json.loads((SCHEMAS / '1.json').read_text(encoding='utf-8'))
    v2 = json.loads((SCHEMAS / '2.json').read_text(encoding='utf-8'))
    source = GENERATED.read_text(encoding='utf-8')
    statements = [s.encode('utf-8').decode('unicode_escape') for s in re.findall(r'execSQL\("((?:[^"\\]|\\.)*)"\)', source)]
    if not statements:
        print('No migration SQL found; build the app first.')
        return 1

    old = sqlite3.connect(':memory:')
    for sql in create_statements(v1):
        old.execute(sql)
    old.execute("INSERT INTO patients VALUES ('ABH-ANDROID-0001','Synthetic Demo Patient',1,1,'2026-10-01T00:00:00Z')")
    old.execute("INSERT INTO screenings VALUES ('scr-legacy-1','ABH-ANDROID-0001','maternal_anemia',9.4,'g/dL','DEMO_MODERATE',0.82,0.7,'DEMO',1,1,'2026-10-01T00:00:00Z')")
    for sql in statements:
        old.execute(sql)

    expected = sqlite3.connect(':memory:')
    for sql in create_statements(v2):
        expected.execute(sql)

    ok = True
    for entity in v2['database']['entities']:
        table = entity['tableName']
        got, want = columns(old, table), columns(expected, table)
        if got != want:
            ok = False
            print(f'MISMATCH in {table}:\n  migrated: {got}\n  expected: {want}')
        else:
            print(f'OK  {table}: {len(got)} columns match 2.json')

    row = old.execute("SELECT estimate, resultMode, syncState, syncAttempts, metadataJson, workerId, caseId FROM screenings WHERE screeningId='scr-legacy-1'").fetchone()
    print('v1 row after migration:', row)
    ok &= row == (9.4, 'DEMO_SYNTHETIC', 'PENDING', 0, '{}', 'demo-worker', None)
    kind = old.execute("SELECT subjectKind FROM patients").fetchone()[0]
    ok &= kind == 'UNSPECIFIED'
    old.execute("INSERT INTO screenings (screeningId,patientId,screeningType,estimate,unit,demoTag,confidencePlaceholder,captureQualityProxy,modelMode,synthetic,confirmationRequired,createdAtUtc) VALUES ('scr-null','ABH-ANDROID-0001','neonatal_jaundice',NULL,'mg/dL','NONE',0,0,'NONE',0,1,'2026-10-02T00:00:00Z')")
    print('Nullable estimate accepted after migration.')
    print('RESULT:', 'PASS' if ok else 'FAIL')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
