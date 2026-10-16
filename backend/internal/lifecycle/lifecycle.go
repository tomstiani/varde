// Package lifecycle owns durable alert acceptance, credentials, and presentation state.
package lifecycle

import (
	"context"
	"database/sql"
	"time"
)

type Lifecycle struct {
	db                 *sql.DB
	now                func() time.Time
	beforeAcceptCommit func(*sql.Conn) error
	afterAcceptCommit  func()
}

func (s *Lifecycle) Close() error { return s.db.Close() }

// A pinned connection allows rollback even when SQLite rejects COMMIT.
func (s *Lifecycle) transaction(ctx context.Context, fn func(*sql.Conn) error) error {
	conn, err := s.db.Conn(ctx)
	if err != nil {
		return err
	}
	defer conn.Close()

	if _, err = conn.ExecContext(ctx, "BEGIN IMMEDIATE"); err != nil {
		return err
	}
	defer func() {
		cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_, _ = conn.ExecContext(cleanup, "ROLLBACK")
	}()

	if err = fn(conn); err != nil {
		return err
	}
	_, err = conn.ExecContext(ctx, "COMMIT")
	return err
}
