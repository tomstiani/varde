package lifecycle

import (
	"context"
	"database/sql"
	"encoding/json"
)

type HistoryQuery struct {
	Limit  int
	Order  string
	Cursor string
}

type HistoryItem struct {
	ID           string       `json:"id"`
	AcceptedAt   string       `json:"acceptedAt"`
	Title        string       `json:"title"`
	Priority     string       `json:"priority"`
	ObservedAt   string       `json:"observedAt"`
	Presentation Presentation `json:"presentation"`
}

type HistoryPage struct {
	Items      []HistoryItem `json:"items"`
	NextCursor *string       `json:"nextCursor"`
}

func (s *Lifecycle) History(ctx context.Context, token string, query HistoryQuery) (HistoryPage, error) {
	page := HistoryPage{Items: []HistoryItem{}}
	err := s.transaction(ctx, func(conn *sql.Conn) error {
		if _, err := authenticate(ctx, conn, token, Installation); err != nil {
			return err
		}
		if query.Limit == 0 {
			query.Limit = 50
		}
		if query.Order == "" {
			query.Order = "acceptance"
		}
		if query.Limit < 1 || query.Limit > 100 || (query.Order != "acceptance" && query.Order != "acceptedAt") {
			return failure("invalid_arguments")
		}
		key, err := historyKey(ctx, conn)
		if err != nil {
			return err
		}
		position := historyPosition{Version: 1, Order: query.Order, Limit: query.Limit}
		if query.Cursor != "" {
			position, err = decodeCursor(query.Cursor, key)
			if err != nil {
				return err
			}
			if position.Order != query.Order || position.Limit != query.Limit {
				return failure("invalid_cursor")
			}
		} else {
			if err := conn.QueryRowContext(ctx, "SELECT COALESCE(MAX(seq),0) FROM alerts").Scan(&position.Watermark); err != nil {
				return err
			}
			position.Sequence = position.Watermark + 1
		}
		rows, err := queryHistory(ctx, conn, position, query.Cursor != "")
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			var item HistoryItem
			var original []byte
			var sequence, seconds int64
			var nanos int
			if err := rows.Scan(&sequence, &item.ID, &item.AcceptedAt, &original, &item.Presentation.State, &item.Presentation.Revision, &seconds, &nanos); err != nil {
				return err
			}
			if len(page.Items) == query.Limit {
				cursor, err := encodeCursor(position, key)
				if err != nil {
					return err
				}
				page.NextCursor = &cursor
				break
			}
			var source Original
			if err := json.Unmarshal(original, &source); err != nil {
				return err
			}
			item.Title, item.Priority, item.ObservedAt = source.Title, source.Priority, source.ObservedAt
			page.Items = append(page.Items, item)
			position.Sequence = sequence
			position.Seconds, position.Nanos, position.ID = seconds, nanos, item.ID
		}
		return rows.Err()
	})
	if err != nil {
		return HistoryPage{}, safe(err)
	}
	return page, nil
}
