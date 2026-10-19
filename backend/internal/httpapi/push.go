package httpapi

import (
	"errors"
	"io"
	"net/http"
	"strconv"

	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

func (h handler) pushRegistration(w http.ResponseWriter, r *http.Request) {
	if err := h.store.Authorize(r.Context(), bearer(r), lifecycle.Installation); err != nil {
		writeError(w, err)
		return
	}
	if r.URL.RawQuery != "" {
		writeError(w, &lifecycle.Failure{Category: "invalid_arguments"})
		return
	}
	var registration lifecycle.PushRegistration
	var err error
	switch r.Method {
	case http.MethodGet:
		err = emptyPushBody(r)
		if err == nil {
			registration, err = h.store.PushRegistration(r.Context(), bearer(r))
		}
	case http.MethodPut, http.MethodDelete:
		var mutation lifecycle.PushMutation
		mutation, err = pushMutationHeaders(r)
		if err != nil {
			break
		}
		if r.Method == http.MethodDelete {
			err = emptyPushBody(r)
			if err == nil {
				registration, err = h.store.DeletePush(r.Context(), bearer(r), mutation)
			}
		} else {
			var body []byte
			r.Body = http.MaxBytesReader(w, r.Body, lifecycle.MaxPushBodyBytes)
			body, err = readJSONBody(w, r)
			var reported *lifecycle.Failure
			if errors.As(err, &reported) && reported.Category == "body_too_large" {
				err = &lifecycle.Failure{Category: "invalid_arguments"}
			}
			if err == nil {
				registration, err = h.store.PutPush(r.Context(), bearer(r), mutation, body)
			}
		}
	}
	if err != nil {
		writeError(w, err)
		return
	}
	w.Header().Set("ETag", strconv.Quote(strconv.FormatInt(registration.Version, 10)))
	writeJSON(w, http.StatusOK, registration)
}

func emptyPushBody(r *http.Request) error {
	body, err := io.ReadAll(io.LimitReader(r.Body, 1))
	if err != nil || len(body) != 0 {
		return &lifecycle.Failure{Category: "invalid_arguments"}
	}
	return nil
}

func pushMutationHeaders(r *http.Request) (lifecycle.PushMutation, error) {
	invalid := &lifecycle.Failure{Category: "invalid_arguments"}
	matches, keys := r.Header.Values("If-Match"), r.Header.Values("Idempotency-Key")
	if len(matches) != 1 || len(keys) != 1 {
		return lifecycle.PushMutation{}, invalid
	}
	etag, key := matches[0], keys[0]
	if len(etag) < 3 || etag[0] != '"' || etag[len(etag)-1] != '"' || len(key) != 32 {
		return lifecycle.PushMutation{}, invalid
	}
	for _, digit := range key {
		if !(digit >= '0' && digit <= '9' || digit >= 'a' && digit <= 'f') {
			return lifecycle.PushMutation{}, invalid
		}
	}
	digits := etag[1 : len(etag)-1]
	version, err := strconv.ParseInt(digits, 10, 64)
	if err != nil || version < 0 || strconv.FormatInt(version, 10) != digits {
		return lifecycle.PushMutation{}, invalid
	}
	return lifecycle.PushMutation{Version: version, Key: key}, nil
}
