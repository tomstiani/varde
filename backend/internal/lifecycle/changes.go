package lifecycle

import (
	"context"
	"database/sql"
)

type Checkpoint struct {
	Cursor string `json:"cursor"`
}
type ChangesQuery struct {
	Cursor string
	Limit  int
}
type Change struct {
	AlertID  string `json:"alertId"`
	Kind     string `json:"kind"`
	Revision int    `json:"revision"`
}
type ChangesPage struct {
	Items      []Change `json:"items"`
	NextCursor string   `json:"nextCursor"`
	HasMore    bool     `json:"hasMore"`
}

func (s *Lifecycle) Checkpoint(ctx context.Context, token string) (Checkpoint, error) {
	var checkpoint Checkpoint
	err := s.transaction(ctx, func(conn *sql.Conn) error {
		if _, err := authenticate(ctx, conn, token, Installation); err != nil {
			return err
		}
		key, err := historyKey(ctx, conn)
		if err != nil {
			return err
		}
		var after int64
		if err := conn.QueryRowContext(ctx, "SELECT COALESCE(MAX(seq),0) FROM changes").Scan(&after); err != nil {
			return err
		}
		checkpoint.Cursor, err = encodeChangesCursor(changePosition{Version: 1, After: after}, key)
		return err
	})
	if err != nil {
		return Checkpoint{}, safe(err)
	}
	return checkpoint, nil
}

func (s *Lifecycle) Changes(ctx context.Context, token string, query ChangesQuery) (ChangesPage, error) {
	page := ChangesPage{Items: []Change{}}
	err := s.transaction(ctx, func(conn *sql.Conn) error {
		if _, err := authenticate(ctx, conn, token, Installation); err != nil {
			return err
		}
		if query.Limit == 0 {
			query.Limit = 50
		}
		if query.Limit < 1 || query.Limit > 100 {
			return failure("invalid_arguments")
		}
		key, err := historyKey(ctx, conn)
		if err != nil {
			return err
		}
		position, err := decodeChangesCursor(query.Cursor, key)
		if err != nil {
			return err
		}
		var end int64
		if err := conn.QueryRowContext(ctx, "SELECT COALESCE(MAX(seq),0) FROM changes").Scan(&end); err != nil {
			return err
		}
		if position.After > end || (position.Horizon != nil && *position.Horizon > end) {
			return failure("sync_reset_required")
		}
		if position.Horizon != nil && position.Limit != query.Limit {
			return failure("invalid_cursor")
		}
		if position.Horizon == nil {
			position.Horizon = &end
			position.Limit = query.Limit
		}
		rows, err := conn.QueryContext(ctx, "SELECT seq,alert_id,revision FROM changes WHERE seq>? AND seq<=? ORDER BY seq LIMIT ?", position.After, *position.Horizon, query.Limit+1)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			var sequence int64
			var item Change
			if err := rows.Scan(&sequence, &item.AlertID, &item.Revision); err != nil {
				return err
			}
			if len(page.Items) == query.Limit {
				page.HasMore = true
				break
			}
			item.Kind = "presentation"
			if item.Revision == 0 {
				item.Kind = "accepted"
			}
			page.Items = append(page.Items, item)
			position.After = sequence
		}
		if err := rows.Err(); err != nil {
			return err
		}
		if !page.HasMore {
			position.After = *position.Horizon
			position.Horizon = nil
			position.Limit = 0
		}
		page.NextCursor, err = encodeChangesCursor(position, key)
		return err
	})
	if err != nil {
		return ChangesPage{}, safe(err)
	}
	return page, nil
}
