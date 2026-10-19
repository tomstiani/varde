package lifecycle

import (
	"context"
	"database/sql"
	"fmt"
)

func migrateRichViews(ctx context.Context, conn *sql.Conn) error {
	if _, err := conn.ExecContext(ctx, richSchema); err != nil {
		return err
	}
	rows, err := conn.QueryContext(ctx, "PRAGMA foreign_key_check")
	if err != nil {
		return err
	}
	defer rows.Close()
	if rows.Next() {
		return fmt.Errorf("rich schema foreign key check failed")
	}
	return rows.Err()
}
