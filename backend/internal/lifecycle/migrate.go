package lifecycle

import (
	"context"
	"crypto/rand"
	"database/sql"
	"time"
)

func migrateAcceptanceInstants(ctx context.Context, conn *sql.Conn) error {
	if _, err := conn.ExecContext(ctx, `ALTER TABLE alerts ADD COLUMN accepted_seconds INTEGER NOT NULL DEFAULT 0;
 ALTER TABLE alerts ADD COLUMN accepted_nanos INTEGER NOT NULL DEFAULT 0;`); err != nil {
		return err
	}
	// Page the one-time backfill rather than retaining the archive in memory.
	var after int64
	for {
		var seq int64
		var spelling string
		err := conn.QueryRowContext(ctx, "SELECT seq,accepted_at FROM alerts WHERE seq>? ORDER BY seq LIMIT 1", after).Scan(&seq, &spelling)
		if err == sql.ErrNoRows {
			break
		}
		if err != nil {
			return err
		}
		instant, err := time.Parse(time.RFC3339Nano, spelling)
		if err != nil {
			return err
		}
		if _, err := conn.ExecContext(ctx, "UPDATE alerts SET accepted_seconds=?,accepted_nanos=? WHERE seq=?", instant.Unix(), instant.Nanosecond(), seq); err != nil {
			return err
		}
		after = seq
	}
	_, err := conn.ExecContext(ctx, "CREATE INDEX alerts_by_instant ON alerts(accepted_seconds DESC,accepted_nanos DESC,id DESC)")
	return err
}

func migrateHistory(ctx context.Context, conn *sql.Conn) error {
	if _, err := conn.ExecContext(ctx, `CREATE TABLE history_metadata (
 singleton INTEGER PRIMARY KEY CHECK(singleton=1),
 cursor_key BLOB NOT NULL CHECK(length(cursor_key)=32)
 )`); err != nil {
		return err
	}
	key := make([]byte, 32)
	if _, err := rand.Read(key); err != nil {
		return err
	}
	if _, err := conn.ExecContext(ctx, "INSERT INTO history_metadata(singleton,cursor_key) VALUES(1,?)", key); err != nil {
		return err
	}
	if err := migrateAcceptanceInstants(ctx, conn); err != nil {
		return err
	}
	_, err := conn.ExecContext(ctx, "PRAGMA user_version=2")
	return err
}
