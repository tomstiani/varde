// Package lifecycle owns durable alert acceptance, credentials, and presentation state.
package lifecycle

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"time"
)

type Lifecycle struct {
	db                 *sql.DB
	now                func() time.Time
	retryJitter        func(time.Duration) time.Duration
	beforeAcceptCommit func(*sql.Conn) error
	beforeWorkerCommit func(*sql.Conn) error
	afterAcceptCommit  func()
}

func (s *Lifecycle) Close() error { return s.db.Close() }

// A pinned connection allows rollback even when SQLite rejects COMMIT.
func (s *Lifecycle) transaction(ctx context.Context, fn func(*sql.Conn) error) (err error) {
	conn, err := s.db.Conn(ctx)
	if err != nil {
		return err
	}
	defer conn.Close()

	if _, err = conn.ExecContext(ctx, "BEGIN IMMEDIATE"); err != nil {
		return err
	}
	committed := false
	defer func() {
		if committed {
			return
		}
		cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if _, rollbackError := conn.ExecContext(cleanup, "ROLLBACK"); rollbackError != nil {
			// Discard the connection rather than pooling an uncertain transaction.
			_ = conn.Raw(func(any) error { return driver.ErrBadConn })
			err = failure("storage_unavailable")
		}
	}()

	if err = fn(conn); err != nil {
		return err
	}
	_, err = conn.ExecContext(ctx, "COMMIT")
	committed = err == nil
	return err
}
