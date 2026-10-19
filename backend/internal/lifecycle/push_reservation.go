package lifecycle

import (
	"context"
	"database/sql"
	"errors"
	"time"
)

type pushReservation struct {
	id           int64
	installation string
	version      int64
	attempt      int
	message      PushMessage
}

func (d *PushDispatcher) reserve(ctx context.Context) (pushReservation, bool, error) {
	var reservation pushReservation
	found := false
	err := d.store.transaction(ctx, func(conn *sql.Conn) error {
		err := conn.QueryRowContext(ctx, `
   SELECT w.id,w.installation_id,w.registration_version,w.attempts,p.provider,p.destination,p.registration_id,w.alert_id
   FROM push_work w JOIN push_registrations p ON p.installation_id=w.installation_id
   JOIN credentials c ON c.id=p.credential_id
   WHERE w.finished=0 AND w.attempts<4 AND w.next_at<=?
    AND w.registration_version=p.version AND p.destination IS NOT NULL AND p.registration_id IS NOT NULL AND c.revoked=0
    AND NOT EXISTS(SELECT 1 FROM push_provider_state s WHERE s.provider=p.provider AND s.next_at>?)
   ORDER BY w.next_at,w.id LIMIT 1
  `, d.now().UnixMilli(), d.now().UnixMilli()).Scan(&reservation.id, &reservation.installation, &reservation.version, &reservation.attempt, &reservation.message.Provider, &reservation.message.Destination, &reservation.message.Data.RegistrationID, &reservation.message.Data.AlertID)
		if errors.Is(err, sql.ErrNoRows) {
			return nil
		}
		if err != nil {
			return err
		}
		reservation.attempt++
		reservation.message.Data.Version = "1"
		// A lost completion counts as an uncertain attempt and waits out the send deadline.
		next := d.now().Add(d.timeout + d.retryDelay(reservation.attempt, 0))
		_, err = conn.ExecContext(ctx, "UPDATE push_work SET attempts=?,next_at=? WHERE id=?", reservation.attempt, next.UnixMilli(), reservation.id)
		found = err == nil
		return err
	})
	return reservation, found, err
}

func (d *PushDispatcher) current(ctx context.Context, reservation pushReservation) (bool, error) {
	current := false
	err := d.store.transaction(ctx, func(conn *sql.Conn) error {
		var exists int
		err := conn.QueryRowContext(ctx, `
   SELECT 1 FROM push_work w
   JOIN push_registrations p ON p.installation_id=w.installation_id
   JOIN credentials c ON c.id=p.credential_id
   WHERE w.id=? AND w.attempts=? AND w.finished=0 AND w.registration_version=p.version
    AND p.destination IS NOT NULL AND p.registration_id IS NOT NULL AND c.revoked=0
  `, reservation.id, reservation.attempt).Scan(&exists)
		if errors.Is(err, sql.ErrNoRows) {
			return nil
		}
		current = err == nil
		return err
	})
	return current, err
}

func (d *PushDispatcher) complete(ctx context.Context, reservation pushReservation, result PushSendResult) (string, error) {
	status := "delivered"
	finished := result.Outcome == PushDelivered || result.Outcome == PushPermanent || reservation.attempt == 4 || result.RetryAfter > 15*time.Minute
	if result.Outcome != PushDelivered {
		status = "retry_scheduled"
		if finished {
			status = "exhausted"
		}
	}
	if result.Outcome == PushPermanent {
		status = "destination_disabled"
	}
	wait := min(max(result.RetryAfter, 0), 15*time.Minute)
	if result.Outcome == PushConfiguration {
		status = "configuration_error"
		wait = max(wait, 5*time.Minute)
	}
	next := d.now().Add(d.retryDelay(reservation.attempt, wait))
	err := d.store.transaction(ctx, func(conn *sql.Conn) error {
		updated, err := conn.ExecContext(ctx, "UPDATE push_work SET finished=?,next_at=? WHERE id=? AND attempts=? AND finished=0", finished, next.UnixMilli(), reservation.id, reservation.attempt)
		if err != nil {
			return err
		}
		count, err := updated.RowsAffected()
		if err != nil {
			return err
		}
		if count == 0 {
			status = "superseded"
			return nil
		}
		if result.Outcome == PushConfiguration {
			_, err = conn.ExecContext(ctx, `INSERT INTO push_provider_state(provider,next_at) VALUES(?,?)
				ON CONFLICT(provider) DO UPDATE SET next_at=MAX(next_at,excluded.next_at)`, reservation.message.Provider, next.UnixMilli())
			if err != nil {
				return err
			}
		}
		if result.Outcome == PushPermanent {
			_, err = conn.ExecContext(ctx, `UPDATE push_registrations SET destination=NULL,version=version+1
				WHERE installation_id=? AND version=?`, reservation.installation, reservation.version)
			if err != nil {
				return err
			}
			return invalidatePushWork(ctx, conn, reservation.installation)
		}
		return err
	})
	return status, err
}
