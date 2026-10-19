package lifecycle

import (
	"context"
	"database/sql"
)

func (s *Lifecycle) DeletePush(ctx context.Context, token string, mutation PushMutation) (PushRegistration, error) {
	if mutation.Version < 0 || !validPushIdentifier(mutation.Key) {
		return PushRegistration{}, failure("invalid_arguments")
	}
	fingerprint := pushFingerprint("DELETE", mutation.Version, pushDestination{})
	var result PushRegistration
	err := s.transaction(ctx, func(conn *sql.Conn) error {
		installation, err := authenticate(ctx, conn, token, Installation)
		if err != nil {
			return err
		}
		replay, found, err := replayPushMutation(ctx, conn, installation, mutation.Key, fingerprint)
		if err != nil {
			return err
		}
		if found {
			result = replay
			return nil
		}
		current, err := readPushRegistration(ctx, conn, installation)
		if err != nil {
			return err
		}
		if current.Version != mutation.Version {
			return failure("registration_changed")
		}
		result.Version = current.Version + 1
		_, err = conn.ExecContext(ctx, `
			INSERT INTO push_registrations(installation_id,version) VALUES(?,?)
			ON CONFLICT(installation_id) DO UPDATE SET
				version=excluded.version,
				registration_id=NULL,
				provider=NULL,
				destination=NULL,
				baseline_cursor=NULL,
				credential_id=NULL
		`, installation, result.Version)
		if err != nil {
			return err
		}
		if err := invalidatePushWork(ctx, conn, installation); err != nil {
			return err
		}
		return recordPushMutation(ctx, conn, installation, mutation.Key, fingerprint, result)
	})
	if err != nil {
		return PushRegistration{}, safe(err)
	}
	return result, nil
}
