package lifecycle

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"time"
)

func (s *Lifecycle) Submit(ctx context.Context, token string, body []byte) (Alert, bool, error) {
	original, validationErr := decodeOriginal(body)
	var result Alert
	created := false
	err := s.transaction(ctx, func(conn *sql.Conn) error {
		owner, err := authenticate(ctx, conn, token, Sender)
		if err != nil {
			return err
		}
		if validationErr != nil {
			return validationErr
		}
		canonical, err := encodeOriginal(original)
		if err != nil {
			return err
		}

		if existing, found, err := findDuplicate(ctx, conn, owner, original.EventID, canonical); err != nil || found {
			result = existing
			return err
		}
		now := time.Now()
		if s.now != nil {
			now = s.now()
		}
		result, err = insertPendingAlert(ctx, conn, owner, original, canonical, now)
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

func encodeOriginal(original Original) ([]byte, error) {
	var buffer bytes.Buffer
	encoder := json.NewEncoder(&buffer)
	// Originals are JSON, not HTML; escaping supplied labels can exceed the detail byte bound.
	encoder.SetEscapeHTML(false)
	if err := encoder.Encode(original); err != nil {
		return nil, err
	}
	return bytes.TrimSuffix(buffer.Bytes(), []byte("\n")), nil
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
	if !equivalentOriginals(previous, canonical) {
		return Alert{}, true, failure("identity_conflict")
	}
	alert, err := readAlert(ctx, conn, id)
	return alert, true, err
}

func insertPendingAlert(ctx context.Context, conn *sql.Conn, owner string, original Original, canonical []byte, now time.Time) (Alert, error) {
	var count int
	if err := conn.QueryRowContext(ctx, "SELECT COUNT(*) FROM work WHERE finished=0").Scan(&count); err != nil {
		return Alert{}, err
	}
	if count >= MaxUnfinished {
		return Alert{}, failure("backlog_full")
	}

	alert := Alert{
		ID:           randomID("alert_"),
		AcceptedAt:   now.UTC().Format(time.RFC3339Nano),
		Original:     original,
		Presentation: Presentation{State: "pending", Revision: 0},
	}
	_, err := conn.ExecContext(ctx,
		"INSERT INTO alerts(id,sender_id,event_id,accepted_at,original,accepted_seconds,accepted_nanos) VALUES(?,?,?,?,?,?,?)",
		alert.ID, owner, original.EventID, alert.AcceptedAt, canonical, now.Unix(), now.Nanosecond(),
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
	if _, err := conn.ExecContext(ctx, `
		INSERT INTO push_work(installation_id,registration_version,alert_id)
		SELECT p.installation_id,p.version,?
		FROM push_registrations p JOIN credentials c ON c.id=p.credential_id
		WHERE p.registration_id IS NOT NULL AND p.destination IS NOT NULL AND c.revoked=0
	`, alert.ID); err != nil {
		return Alert{}, err
	}
	return alert, nil
}
