package lifecycle

import (
	"context"
	"database/sql"
	_ "embed"
	"net/url"
	"os"
	"path/filepath"
	"time"

	_ "modernc.org/sqlite"
)

//go:embed schema.sql
var schema string

//go:embed push_schema.sql
var pushSchema string

//go:embed push_work_schema.sql
var pushWorkSchema string

//go:embed generation_schema.sql
var generationSchema string

func Open(path string) (*Lifecycle, error) {
	if path == "" || path == ":memory:" {
		return nil, failure("invalid_database_path")
	}
	abs, err := filepath.Abs(path)
	if err != nil {
		return nil, safe(err)
	}
	if err = prepareDatabaseFile(abs); err != nil {
		return nil, safe(err)
	}

	db, err := sql.Open("sqlite", databaseURL(abs))
	if err != nil {
		return nil, safe(err)
	}
	db.SetMaxOpenConns(1)
	store := &Lifecycle{db: db}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err = store.initializeSchema(ctx); err != nil {
		db.Close()
		return nil, safe(err)
	}
	return store, nil
}

func prepareDatabaseFile(path string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return err
	}
	file, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return err
	}
	info, statErr := file.Stat()
	closeErr := file.Close()
	if statErr != nil {
		return statErr
	}
	if closeErr != nil {
		return closeErr
	}
	if !info.Mode().IsRegular() || info.Mode().Perm()&0077 != 0 {
		return failure("insecure_database_permissions")
	}
	return nil
}

func databaseURL(path string) string {
	u := url.URL{Scheme: "file", Path: path}
	query := url.Values{}
	for _, pragma := range []string{"busy_timeout(1000)", "foreign_keys(ON)", "journal_mode(WAL)", "synchronous(FULL)"} {
		query.Add("_pragma", pragma)
	}
	u.RawQuery = query.Encode()
	return u.String()
}

func (s *Lifecycle) initializeSchema(ctx context.Context) error {
	return s.transaction(ctx, func(conn *sql.Conn) error {
		var version int
		if err := conn.QueryRowContext(ctx, "PRAGMA user_version").Scan(&version); err != nil {
			return err
		}
		switch version {
		case 5:
			return nil
		case 4:
			_, err := conn.ExecContext(ctx, generationSchema)
			return err
		case 3:
			if _, err := conn.ExecContext(ctx, pushWorkSchema); err != nil {
				return err
			}
			_, err := conn.ExecContext(ctx, generationSchema)
			return err
		case 2:
		case 1:
			if err := migrateHistory(ctx, conn); err != nil {
				return err
			}
		case 0:
			if _, err := conn.ExecContext(ctx, schema); err != nil {
				return err
			}
			if err := migrateHistory(ctx, conn); err != nil {
				return err
			}
		default:
			return failure("unsupported_database_version")
		}
		if _, err := conn.ExecContext(ctx, pushSchema); err != nil {
			return err
		}
		if _, err := conn.ExecContext(ctx, pushWorkSchema); err != nil {
			return err
		}
		_, err := conn.ExecContext(ctx, generationSchema)
		return err
	})
}
