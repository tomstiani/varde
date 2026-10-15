package httpapi

import (
	"errors"
	"io"
	"mime"
	"net/http"
	"strings"

	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

func readJSONBody(w http.ResponseWriter, r *http.Request) ([]byte, error) {
	media, parameters, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || media != "application/json" || (parameters["charset"] != "" && !strings.EqualFold(parameters["charset"], "utf-8")) {
		return nil, &lifecycle.Failure{Category: "unsupported_media"}
	}
	if encoding := r.Header.Get("Content-Encoding"); encoding != "" && !strings.EqualFold(encoding, "identity") {
		return nil, &lifecycle.Failure{Category: "unsupported_media"}
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, lifecycle.MaxBodyBytes))
	if err != nil {
		var limit *http.MaxBytesError
		if errors.As(err, &limit) {
			return nil, &lifecycle.Failure{Category: "body_too_large"}
		}
		return nil, &lifecycle.Failure{Category: "invalid_json"}
	}
	return body, nil
}

func bearer(r *http.Request) string {
	values := r.Header.Values("Authorization")
	if len(values) != 1 {
		return ""
	}
	scheme, token, ok := strings.Cut(values[0], " ")
	if !ok || !strings.EqualFold(scheme, "Bearer") || strings.ContainsAny(token, " \t\r\n") {
		return ""
	}
	return token
}
