CREATE TABLE rich_alerts (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    id TEXT NOT NULL UNIQUE,
    sender_id TEXT NOT NULL REFERENCES identities(id),
    event_id TEXT NOT NULL,
    accepted_at TEXT NOT NULL,
    original BLOB NOT NULL,
    state TEXT NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'fallback', 'rich')),
    revision INTEGER NOT NULL DEFAULT 0,
    category TEXT,
    accepted_seconds INTEGER NOT NULL DEFAULT 0,
    accepted_nanos INTEGER NOT NULL DEFAULT 0,
    rich_view BLOB,
    UNIQUE (sender_id, event_id),
    CHECK ((state='rich') = (rich_view IS NOT NULL))
);

INSERT INTO rich_alerts(seq,id,sender_id,event_id,accepted_at,original,state,revision,category,accepted_seconds,accepted_nanos)
SELECT seq,id,sender_id,event_id,accepted_at,original,state,revision,category,accepted_seconds,accepted_nanos FROM alerts;
DROP TABLE alerts;
ALTER TABLE rich_alerts RENAME TO alerts;
CREATE INDEX alerts_by_instant ON alerts(accepted_seconds DESC,accepted_nanos DESC,id DESC);
PRAGMA user_version = 5;
