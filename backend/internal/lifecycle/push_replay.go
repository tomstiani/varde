package lifecycle

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/json"
	"errors"
)

func pushFingerprint(method string, version int64, destination pushDestination) []byte {
	canonical, _ := json.Marshal(struct {
		Method      string
		Version     int64
		Destination pushDestination
	}{method, version, destination})
	digest := sha256.Sum256(canonical)
	return digest[:]
}

func replayPushMutation(ctx context.Context, conn *sql.Conn, installation, key string, fingerprint []byte) (PushRegistration, bool, error) {
	var result PushRegistration
	var storedHash, encoded []byte
	var version int64
	err := conn.QueryRowContext(ctx, "SELECT request_hash,result_json,result_version FROM push_mutations WHERE installation_id=? AND idempotency_key=?", installation, key).Scan(&storedHash, &encoded, &version)
	if errors.Is(err, sql.ErrNoRows) {
		return result, false, nil
	}
	if err != nil {
		return result, false, err
	}
	if !bytes.Equal(storedHash, fingerprint) {
		return result, false, failure("idempotency_conflict")
	}
	if err := json.Unmarshal(encoded, &result); err != nil {
		return result, false, err
	}
	result.Version = version
	return result, true, nil
}

func recordPushMutation(ctx context.Context, conn *sql.Conn, installation, key string, fingerprint []byte, result PushRegistration) error {
	encoded, err := json.Marshal(result)
	if err != nil {
		return err
	}
	_, err = conn.ExecContext(ctx, "INSERT INTO push_mutations(installation_id,idempotency_key,request_hash,result_json,result_version) VALUES(?,?,?,?,?)", installation, key, fingerprint, encoded, result.Version)
	return err
}
