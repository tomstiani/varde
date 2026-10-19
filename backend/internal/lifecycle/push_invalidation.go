package lifecycle

import (
	"context"
	"database/sql"
)

func invalidatePushWork(ctx context.Context, conn *sql.Conn, installation string) error {
	_, err := conn.ExecContext(ctx, `
  UPDATE push_work SET finished=1
  WHERE installation_id=? AND finished=0 AND NOT EXISTS(
   SELECT 1 FROM push_registrations p JOIN credentials c ON c.id=p.credential_id
   WHERE p.installation_id=push_work.installation_id AND p.version=push_work.registration_version
    AND p.destination IS NOT NULL AND p.registration_id IS NOT NULL AND c.revoked=0
  )
 `, installation)
	return err
}
