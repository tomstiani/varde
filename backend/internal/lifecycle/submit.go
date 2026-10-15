package lifecycle

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"time"
)

func (s *Lifecycle) Submit(ctx context.Context, token string, body []byte) (Alert, bool, error) {
	var result Alert
	created := false
	err := s.transaction(ctx, func(conn *sql.Conn) error {
		owner, err := authenticate(ctx, conn, token, Sender)
		if err != nil {
			return err
		}
		original, err := decodeOriginal(body)
		if err != nil {
			return err
		}
		canonical, err := json.Marshal(original)
		if err != nil {
			return err
		}

		if existing, found, err := findDuplicate(ctx, conn, owner, original.EventID, canonical); err != nil || found {
			result = existing
			return err
		}
		result, err = insertPendingAlert(ctx, conn, owner, original, canonical)
		if err != nil {
			return err
		}
		if s.beforeAcceptCommit != nil {
			if err = s.beforeAcceptCommit(conn); err != nil {
				return err
			}
		}
		created = true
		return nil
	})
	if err != nil {
		return Alert{}, false, safe(err)
	}
	if created && s.afterAcceptCommit != nil {
		s.afterAcceptCommit()
	}
	return result, created, nil
}

func findDuplicate(ctx context.Context, conn *sql.Conn, owner, eventID string, canonical []byte) (Alert, bool, error) {
	var id string
	var previous []byte
	err := conn.QueryRowContext(ctx,
		"SELECT id,original FROM alerts WHERE sender_id=? AND event_id=?",
		owner, eventID,
	).Scan(&id, &previous)
	if errors.Is(err, sql.ErrNoRows) {
		return Alert{}, false, nil
	}
	if err != nil {
		return Alert{}, false, err
	}
	if string(previous) != string(canonical) {
		return Alert{}, true, failure("identity_conflict")
	}
	alert, err := readAlert(ctx, conn, id)
	return alert, true, err
}

func insertPendingAlert(ctx context.Context, conn *sql.Conn, owner string, original Original, canonical []byte) (Alert, error) {
	var count int
	if err := conn.QueryRowContext(ctx, "SELECT COUNT(*) FROM work WHERE finished=0").Scan(&count); err != nil {
		return Alert{}, err
	}
	if count >= MaxUnfinished {
		return Alert{}, failure("backlog_full")
	}

	alert := Alert{
		ID:           randomID("alert_"),
		AcceptedAt:   time.Now().UTC().Format(time.RFC3339Nano),
		Original:     original,
		Presentation: Presentation{State: "pending", Revision: 0},
	}
	_, err := conn.ExecContext(ctx,
		"INSERT INTO alerts(id,sender_id,event_id,accepted_at,original) VALUES(?,?,?,?,?)",
		alert.ID, owner, original.EventID, alert.AcceptedAt, canonical,
	)
	if err != nil {
		return Alert{}, err
	}
	if _, err = conn.ExecContext(ctx, "INSERT INTO work(alert_id) VALUES(?)", alert.ID); err != nil {
		return Alert{}, err
	}
	if _, err = conn.ExecContext(ctx, "INSERT INTO changes(alert_id,revision) VALUES(?,0)", alert.ID); err != nil {
		return Alert{}, err
	}
	return alert, nil
}
