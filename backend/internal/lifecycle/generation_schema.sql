CREATE TABLE generation_manifests (
    config_id TEXT PRIMARY KEY,
    account_ref TEXT NOT NULL,
    model TEXT NOT NULL,
    prompt_revision TEXT NOT NULL,
    contract_version INTEGER NOT NULL,
    candidate_version INTEGER NOT NULL
);
CREATE TABLE generation_activation (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    config_id TEXT REFERENCES generation_manifests(config_id)
);
INSERT INTO generation_activation(singleton, config_id) VALUES(1, NULL);
PRAGMA user_version=5;
