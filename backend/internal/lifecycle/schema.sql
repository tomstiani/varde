CREATE TABLE identities (
    id TEXT PRIMARY KEY,
    role TEXT NOT NULL CHECK (role IN ('sender', 'installation')),
    name TEXT NOT NULL
);

CREATE TABLE credentials (
    id TEXT PRIMARY KEY,
    owner_id TEXT NOT NULL REFERENCES identities(id),
    hash BLOB NOT NULL UNIQUE,
    revoked INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE alerts (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    id TEXT NOT NULL UNIQUE,
    sender_id TEXT NOT NULL REFERENCES identities(id),
    event_id TEXT NOT NULL,
    accepted_at TEXT NOT NULL,
    original BLOB NOT NULL,
    state TEXT NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'fallback')),
    revision INTEGER NOT NULL DEFAULT 0,
    category TEXT,
    UNIQUE (sender_id, event_id)
);

CREATE TABLE work (
    alert_id TEXT PRIMARY KEY REFERENCES alerts(id),
    finished INTEGER NOT NULL DEFAULT 0 CHECK (finished IN (0, 1))
);

CREATE INDEX unfinished ON work(finished);

CREATE TABLE changes (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    alert_id TEXT NOT NULL REFERENCES alerts(id),
    revision INTEGER NOT NULL,
    UNIQUE (alert_id, revision)
);

PRAGMA user_version = 1;
