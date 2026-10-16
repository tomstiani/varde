package lifecycle

import (
	"context"
	"database/sql"
	"errors"
	"time"
)

// Run resumes retained work; event delivery never blocks. Keep events open until Run returns.
func (s *Lifecycle) Run(ctx context.Context, events chan<- WorkerEvent) error {
	var recovery workerRecovery
	jitter := retryJitter
	if s.retryJitter != nil {
		jitter = s.retryJitter
	}
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		completed, err := s.completeNext(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			if isStorageBusy(err) {
				if err := waitForRetry(ctx, jitter(recovery.failed(events))); err != nil {
					return err
				}
				continue
			}
			recovery.report(events, "worker_stopped", "storage_unavailable", recovery.failures)
			return safe(err)
		}
		recovery.recovered(events)
		if completed {
			continue
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
		}
	}
}

func (s *Lifecycle) completeNext(ctx context.Context) (bool, error) {
	completed := false
	err := s.transaction(ctx, func(conn *sql.Conn) error {
		var id string
		err := conn.QueryRowContext(ctx, `SELECT a.id FROM alerts a JOIN work w ON a.id=w.alert_id WHERE w.finished=0 ORDER BY a.seq LIMIT 1`).Scan(&id)
		if errors.Is(err, sql.ErrNoRows) {
			return nil
		}
		if err != nil {
			return err
		}
		if _, err = conn.ExecContext(ctx, `UPDATE alerts SET state='fallback',revision=1,category='model_not_configured' WHERE id=? AND state='pending'`, id); err != nil {
			return err
		}
		if _, err = conn.ExecContext(ctx, "UPDATE work SET finished=1 WHERE alert_id=?", id); err != nil {
			return err
		}
		if _, err = conn.ExecContext(ctx, "INSERT INTO changes(alert_id,revision) VALUES(?,1)", id); err != nil {
			return err
		}
		if s.beforeWorkerCommit != nil {
			if err := s.beforeWorkerCommit(conn); err != nil {
				return err
			}
		}
		completed = true
		return nil
	})
	return completed, err
}
