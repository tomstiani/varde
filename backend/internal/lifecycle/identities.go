package lifecycle

import (
	"context"
	"database/sql"
	"strings"
)

func (s *Lifecycle) CreateIdentity(ctx context.Context, role Role, name string) (Identity, error) {
	if (role != Sender && role != Installation) || strings.TrimSpace(name) == "" || len([]rune(name)) > 256 {
		return Identity{}, failure("invalid_identity")
	}
	identity := Identity{ID: randomID("owner_"), Role: role, Name: name}
	err := s.transaction(ctx, func(conn *sql.Conn) error {
		_, err := conn.ExecContext(ctx,
			"INSERT INTO identities(id,role,name) VALUES(?,?,?)",
			identity.ID, identity.Role, identity.Name,
		)
		return err
	})
	if err != nil {
		return Identity{}, safe(err)
	}
	return identity, nil
}
