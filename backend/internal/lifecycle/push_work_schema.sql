ALTER TABLE push_registrations ADD COLUMN credential_id TEXT REFERENCES credentials(id);
UPDATE push_registrations
SET registration_id=NULL,provider=NULL,destination=NULL,baseline_cursor=NULL,version=version+1
WHERE registration_id IS NOT NULL;
CREATE TABLE push_work (
 id INTEGER PRIMARY KEY,
 installation_id TEXT NOT NULL REFERENCES identities(id),
 registration_version INTEGER NOT NULL,
 alert_id TEXT NOT NULL REFERENCES alerts(id),
 attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 4),
 next_at INTEGER NOT NULL DEFAULT 0,
 finished INTEGER NOT NULL DEFAULT 0 CHECK(finished IN (0,1)),
 UNIQUE(installation_id,registration_version,alert_id)
);
CREATE INDEX push_work_due ON push_work(finished,next_at,id);
CREATE TABLE push_provider_state (
 provider TEXT PRIMARY KEY,
 next_at INTEGER NOT NULL
);
PRAGMA user_version=4;
