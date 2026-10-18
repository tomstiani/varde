package httpapi

import (
	"io"
	"net/http"
	"net/url"
	"strconv"

	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

func (h handler) checkpoint(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(io.LimitReader(r.Body, 1))
	if err != nil || len(body) != 0 || r.URL.RawQuery != "" {
		writeError(w, &lifecycle.Failure{Category: "invalid_arguments"})
		return
	}
	checkpoint, err := h.store.Checkpoint(r.Context(), bearer(r))
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, checkpoint)
}

func (h handler) changes(w http.ResponseWriter, r *http.Request) {
	query, err := changesQuery(r.URL.RawQuery)
	if err != nil {
		writeError(w, err)
		return
	}
	page, err := h.store.Changes(r.Context(), bearer(r), query)
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, page)
}

func changesQuery(raw string) (lifecycle.ChangesQuery, error) {
	query := lifecycle.ChangesQuery{Limit: 50}
	invalid := &lifecycle.Failure{Category: "invalid_arguments"}
	if len(raw) > 4096 {
		return query, &lifecycle.Failure{Category: "invalid_cursor"}
	}
	values, err := url.ParseQuery(raw)
	if err != nil {
		return query, invalid
	}
	for name, entries := range values {
		if len(entries) != 1 || entries[0] == "" {
			return query, invalid
		}
		switch name {
		case "cursor":
			if len(entries[0]) > lifecycle.MaxCursorBytes {
				return query, &lifecycle.Failure{Category: "invalid_cursor"}
			}
			query.Cursor = entries[0]
		case "limit":
			for _, digit := range entries[0] {
				if digit < '0' || digit > '9' {
					return query, invalid
				}
			}
			query.Limit, err = strconv.Atoi(entries[0])
			if err != nil || query.Limit < 1 || query.Limit > 100 {
				return query, invalid
			}
		default:
			return query, invalid
		}
	}
	if query.Cursor == "" {
		return query, invalid
	}
	return query, nil
}
