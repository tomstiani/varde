package httpapi

import (
	"errors"
	"net/http"

	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

var errorStatuses = map[string]int{
	"unauthorized":        http.StatusUnauthorized,
	"forbidden":           http.StatusForbidden,
	"not_found":           http.StatusNotFound,
	"identity_conflict":   http.StatusConflict,
	"body_too_large":      http.StatusRequestEntityTooLarge,
	"unsupported_media":   http.StatusUnsupportedMediaType,
	"invalid_json":        http.StatusBadRequest,
	"invalid_field":       http.StatusUnprocessableEntity,
	"backlog_full":        http.StatusServiceUnavailable,
	"storage_unavailable": http.StatusServiceUnavailable,
}

func writeError(w http.ResponseWriter, err error) {
	failure := &lifecycle.Failure{Category: "storage_unavailable"}
	var reported *lifecycle.Failure
	if errors.As(err, &reported) {
		failure = reported
	}
	status, known := errorStatuses[failure.Category]
	if !known {
		status = http.StatusServiceUnavailable
	}
	message := failure.Message()
	if failure.Category == "unsupported_media" {
		message = "Use uncompressed UTF-8 application/json."
	}

	if status == http.StatusUnauthorized {
		w.Header().Set("WWW-Authenticate", `Bearer realm="varde"`)
	}
	if status == http.StatusServiceUnavailable {
		w.Header().Set("Retry-After", "5")
	}
	writeJSON(w, status, struct {
		Error   string `json:"error"`
		Message string `json:"message"`
		Field   string `json:"field,omitempty"`
	}{failure.Category, message, failure.Field})
}
