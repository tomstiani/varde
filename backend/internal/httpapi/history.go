package httpapi

import (
	"net/http"
	"net/url"
	"strconv"

	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

func (h handler) history(w http.ResponseWriter, r *http.Request) {
	query, err := historyQuery(r.URL.RawQuery)
	if err != nil {
		writeError(w, err)
		return
	}
	page, err := h.store.History(r.Context(), bearer(r), query)
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, page)
}

func historyQuery(raw string) (lifecycle.HistoryQuery, error) {
	query := lifecycle.HistoryQuery{Limit: 50, Order: "acceptance"}
	invalid := &lifecycle.Failure{Category: "invalid_arguments"}
	// Bound input before percent decoding or signature verification.
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
		value := entries[0]
		switch name {
		case "limit":
			for _, digit := range value {
				if digit < '0' || digit > '9' {
					return query, invalid
				}
			}
			query.Limit, err = strconv.Atoi(value)
			if err != nil || query.Limit < 1 || query.Limit > 100 {
				return query, invalid
			}
		case "order":
			if value != "acceptance" && value != "acceptedAt" {
				return query, invalid
			}
			query.Order = value
		case "cursor":
			if len(value) > lifecycle.MaxCursorBytes {
				return query, &lifecycle.Failure{Category: "invalid_cursor"}
			}
			query.Cursor = value
		default:
			return query, invalid
		}
	}
	return query, nil
}
