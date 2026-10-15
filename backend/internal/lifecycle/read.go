package lifecycle

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
)

func (s *Lifecycle) Read(ctx context.Context, token, id string) (Alert, error) {
	var alert Alert
	err := s.transaction(ctx, func(conn *sql.Conn) error {
		if _, err := authenticate(ctx, conn, token, Installation); err != nil {
			return err
		}
		var err error
		alert, err = readAlert(ctx, conn, id)
		return err
	})
	if err != nil {
		return Alert{}, safe(err)
	}
	return alert, nil
}

func readAlert(ctx context.Context, conn *sql.Conn, id string) (Alert, error) {
	var alert Alert
	var original []byte
	err := conn.QueryRowContext(ctx,
		"SELECT id,accepted_at,original,state,revision FROM alerts WHERE id=?", id,
	).Scan(&alert.ID, &alert.AcceptedAt, &original, &alert.Presentation.State, &alert.Presentation.Revision)
	if errors.Is(err, sql.ErrNoRows) {
		return Alert{}, failure("not_found")
	}
	if err != nil {
		return Alert{}, err
	}
	if err = json.Unmarshal(original, &alert.Original); err != nil {
		return Alert{}, err
	}
	return alert, nil
}
