package lifecycle

import (
	"context"
	"database/sql"
)

func queryHistory(ctx context.Context, conn *sql.Conn, position historyPosition, continuation bool) (*sql.Rows, error) {
	const columns = "SELECT seq,id,accepted_at,original,state,revision,accepted_seconds,accepted_nanos FROM alerts "
	if position.Order == "acceptance" {
		return conn.QueryContext(ctx, columns+"WHERE seq<=? AND seq<? ORDER BY seq DESC LIMIT ?", position.Watermark, position.Sequence, position.Limit+1)
	}
	if !continuation {
		return conn.QueryContext(ctx, columns+"WHERE seq<=? ORDER BY accepted_seconds DESC,accepted_nanos DESC,id DESC LIMIT ?", position.Watermark, position.Limit+1)
	}
	return conn.QueryContext(ctx, columns+"WHERE seq<=? AND (accepted_seconds,accepted_nanos,id)<(?,?,?) ORDER BY accepted_seconds DESC,accepted_nanos DESC,id DESC LIMIT ?", position.Watermark, position.Seconds, position.Nanos, position.ID, position.Limit+1)
}
