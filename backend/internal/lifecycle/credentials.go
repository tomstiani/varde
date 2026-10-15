package lifecycle

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"errors"
)

func (s *Lifecycle) IssueCredential(ctx context.Context, identityID string) (Credential, error) {
	key := Credential{
		ID:         randomID("cred_"),
		IdentityID: identityID,
		Secret:     base64.RawURLEncoding.EncodeToString(randomBytes(32)),
	}
	digest := sha256.Sum256([]byte(key.Secret))
	err := s.transaction(ctx, func(conn *sql.Conn) error {
		var exists int
		err := conn.QueryRowContext(ctx, "SELECT 1 FROM identities WHERE id=?", identityID).Scan(&exists)
		if errors.Is(err, sql.ErrNoRows) {
			return failure("not_found")
		}
		if err != nil {
			return err
		}
		_, err = conn.ExecContext(ctx,
			"INSERT INTO credentials(id,owner_id,hash) VALUES(?,?,?)",
			key.ID, identityID, digest[:],
		)
		return err
	})
	if err != nil {
		return Credential{}, safe(err)
	}
	return key, nil
}

func (s *Lifecycle) RevokeCredential(ctx context.Context, credentialID string) error {
	return safeOrNil(s.transaction(ctx, func(conn *sql.Conn) error {
		result, err := conn.ExecContext(ctx, "UPDATE credentials SET revoked=1 WHERE id=?", credentialID)
		if err != nil {
			return err
		}
		count, err := result.RowsAffected()
		if err == nil && count == 0 {
			return failure("not_found")
		}
		return err
	}))
}

// Authorize rejects access before reading a body; Submit and Read recheck in their transactions.
func (s *Lifecycle) Authorize(ctx context.Context, token string, role Role) error {
	return safeOrNil(s.transaction(ctx, func(conn *sql.Conn) error {
		_, err := authenticate(ctx, conn, token, role)
		return err
	}))
}

func authenticate(ctx context.Context, conn *sql.Conn, token string, required Role) (string, error) {
	if len(token) != 43 {
		return "", failure("unauthorized")
	}
	digest := sha256.Sum256([]byte(token))
	var owner string
	var role Role
	err := conn.QueryRowContext(ctx, `
		SELECT i.id, i.role
		FROM credentials c
		JOIN identities i ON c.owner_id = i.id
		WHERE c.hash = ? AND c.revoked = 0
	`, digest[:]).Scan(&owner, &role)
	if errors.Is(err, sql.ErrNoRows) {
		return "", failure("unauthorized")
	}
	if err != nil {
		return "", err
	}
	if role != required {
		return "", failure("forbidden")
	}
	return owner, nil
}
