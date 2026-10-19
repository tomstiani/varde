package lifecycle

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/json"
	"errors"
	"unicode/utf8"
)

const MaxPushBodyBytes = 16 * 1024

// PushRegistration never exposes a provider destination.
type PushRegistration struct {
	RegistrationID *string `json:"registrationId"`
	Provider       *string `json:"provider"`
	Enabled        bool    `json:"enabled"`
	BaselineCursor *string `json:"baselineCursor"`
	Version        int64   `json:"-"`
}

type PushMutation struct {
	Version int64
	Key     string
}

type pushDestination struct {
	RegistrationID string `json:"registrationId"`
	Provider       string `json:"provider"`
	Destination    string `json:"destination"`
}

func validPushIdentifier(value string) bool {
	if len(value) != 32 {
		return false
	}
	for _, digit := range value {
		if !(digit >= '0' && digit <= '9' || digit >= 'a' && digit <= 'f') {
			return false
		}
	}
	return true
}

func decodePushDestination(body []byte) (pushDestination, error) {
	var destination pushDestination
	if len(body) > MaxPushBodyBytes {
		return destination, failure("invalid_arguments")
	}
	raw, err := decodeOriginalJSON(body)
	if err != nil || len(raw) != 3 {
		return destination, failure("invalid_arguments")
	}
	for _, name := range []string{"registrationId", "provider", "destination"} {
		var value string
		if err := json.Unmarshal(raw[name], &value); err != nil || value == "" {
			return destination, failure("invalid_arguments")
		}
	}
	if err := json.Unmarshal(body, &destination); err != nil {
		return destination, failure("invalid_arguments")
	}
	if !validPushIdentifier(destination.RegistrationID) || destination.Provider != "fcm" || len(destination.Destination) > 8*1024 || !utf8.ValidString(destination.Destination) {
		return destination, failure("invalid_arguments")
	}
	return destination, nil
}

func readPushRegistration(ctx context.Context, conn *sql.Conn, installation string) (PushRegistration, error) {
	var registration PushRegistration
	err := conn.QueryRowContext(ctx, `SELECT version,registration_id,provider,baseline_cursor,
		destination IS NOT NULL
		FROM push_registrations WHERE installation_id=?`, installation).Scan(&registration.Version, &registration.RegistrationID, &registration.Provider, &registration.BaselineCursor, &registration.Enabled)
	if errors.Is(err, sql.ErrNoRows) {
		return PushRegistration{}, nil
	}
	return registration, err
}

func (s *Lifecycle) PushRegistration(ctx context.Context, token string) (PushRegistration, error) {
	var registration PushRegistration
	err := s.transaction(ctx, func(conn *sql.Conn) error {
		installation, err := authenticate(ctx, conn, token, Installation)
		if err != nil {
			return err
		}
		registration, err = readPushRegistration(ctx, conn, installation)
		return err
	})
	if err != nil {
		return PushRegistration{}, safe(err)
	}
	if !registration.Enabled {
		registration.RegistrationID = nil
		registration.Provider = nil
		registration.BaselineCursor = nil
	}
	return registration, nil
}

func (s *Lifecycle) PutPush(ctx context.Context, token string, mutation PushMutation, body []byte) (PushRegistration, error) {
	destination, err := decodePushDestination(body)
	if err != nil {
		return PushRegistration{}, err
	}
	if mutation.Version < 0 || !validPushIdentifier(mutation.Key) {
		return PushRegistration{}, failure("invalid_arguments")
	}
	fingerprint := pushFingerprint("PUT", mutation.Version, destination)
	var result PushRegistration
	err = s.transaction(ctx, func(conn *sql.Conn) error {
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
		baseline := current.BaselineCursor
		if current.RegistrationID == nil || *current.RegistrationID != destination.RegistrationID {
			var used int
			err := conn.QueryRowContext(ctx, "SELECT 1 FROM push_sessions WHERE installation_id=? AND registration_id=?", installation, destination.RegistrationID).Scan(&used)
			if err == nil {
				return failure("registration_changed")
			}
			if !errors.Is(err, sql.ErrNoRows) {
				return err
			}
			if _, err := conn.ExecContext(ctx, "INSERT INTO push_sessions(installation_id,registration_id) VALUES(?,?)", installation, destination.RegistrationID); err != nil {
				return err
			}
			cursor, err := currentChangeCheckpoint(ctx, conn)
			if err != nil {
				return err
			}
			baseline = &cursor
		}
		result = PushRegistration{
			RegistrationID: &destination.RegistrationID,
			Provider:       &destination.Provider,
			Enabled:        true,
			BaselineCursor: baseline,
			Version:        current.Version + 1,
		}
		digest := sha256.Sum256([]byte(token))
		var credentialID string
		if err := conn.QueryRowContext(ctx, "SELECT id FROM credentials WHERE hash=? AND revoked=0", digest[:]).Scan(&credentialID); err != nil {
			return err
		}
		_, err = conn.ExecContext(ctx, `
			INSERT INTO push_registrations(
				installation_id,version,registration_id,provider,destination,baseline_cursor,credential_id
			) VALUES(?,?,?,?,?,?,?)
			ON CONFLICT(installation_id) DO UPDATE SET
				version=excluded.version,
				registration_id=excluded.registration_id,
				provider=excluded.provider,
				destination=excluded.destination,
				baseline_cursor=excluded.baseline_cursor,
				credential_id=excluded.credential_id
		`, installation, result.Version, destination.RegistrationID, destination.Provider, destination.Destination, baseline, credentialID)
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
