// Package httpapi adapts HTTP requests to the alert lifecycle Interface.
package httpapi

import (
	"net/http"

	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

type handler struct {
	store *lifecycle.Lifecycle
}

func New(store *lifecycle.Lifecycle) http.Handler {
	h := handler{store: store}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /v1/installation/push", h.pushRegistration)
	mux.HandleFunc("PUT /v1/installation/push", h.pushRegistration)
	mux.HandleFunc("DELETE /v1/installation/push", h.pushRegistration)
	mux.HandleFunc("POST /v1/changes/checkpoint", h.checkpoint)
	mux.HandleFunc("GET /v1/changes", h.changes)
	mux.HandleFunc("POST /v1/alerts", h.submitAlert)
	mux.HandleFunc("GET /v1/alerts", h.history)
	mux.HandleFunc("GET /v1/alerts/{alertId}", h.readAlert)
	return protectResponses(requestDeadline(mux))
}

func protectResponses(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		response := &safeResponseWriter{ResponseWriter: w}
		w = response
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		defer func() {
			if recover() != nil {
				writeError(w, &lifecycle.Failure{Category: "storage_unavailable"})
			}
			if response.status == http.StatusServiceUnavailable && !response.bodyWritten {
				// TimeoutHandler emits no body if connection cancellation wins the deadline race.
				_, _ = w.Write([]byte(requestTimeoutBody))
			}
		}()
		next.ServeHTTP(w, r)
	})
}
