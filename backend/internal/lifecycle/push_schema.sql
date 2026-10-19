CREATE TABLE push_registrations (
 installation_id TEXT PRIMARY KEY REFERENCES identities(id),
 version INTEGER NOT NULL CHECK(version >= 0),
 registration_id TEXT,
 provider TEXT,
 destination TEXT,
 baseline_cursor TEXT
);
CREATE TABLE push_sessions (
 installation_id TEXT NOT NULL REFERENCES identities(id),
 registration_id TEXT NOT NULL,
 PRIMARY KEY(installation_id,registration_id)
);
CREATE TABLE push_mutations (
 installation_id TEXT NOT NULL REFERENCES identities(id),
 idempotency_key TEXT NOT NULL,
 request_hash BLOB NOT NULL,
 result_json BLOB NOT NULL,
 result_version INTEGER NOT NULL,
 PRIMARY KEY(installation_id,idempotency_key)
);
PRAGMA user_version=3;
