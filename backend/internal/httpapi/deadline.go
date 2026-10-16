package httpapi

import (
	"net/http"
	"time"
)

const requestBudget = 5 * time.Second
const requestTimeoutBody = `{"error":"storage_unavailable","message":"The request timed out; retry with the same event ID."}` + "\n"

func requestDeadline(next http.Handler) http.Handler {
	bounded := http.TimeoutHandler(next, requestBudget, requestTimeoutBody)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Context cancellation alone cannot interrupt a stalled request-body read.
		_ = http.NewResponseController(w).SetReadDeadline(time.Now().Add(requestBudget))
		bounded.ServeHTTP(w, r)
	})
}

type safeResponseWriter struct {
	http.ResponseWriter
	status      int
	bodyWritten bool
}

func (w *safeResponseWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }

func (w *safeResponseWriter) Write(data []byte) (int, error) {
	n, err := w.ResponseWriter.Write(data)
	w.bodyWritten = w.bodyWritten || n > 0
	return n, err
}

func (w *safeResponseWriter) WriteHeader(status int) {
	w.status = status
	if status == http.StatusServiceUnavailable {
		// TimeoutHandler supplies the body but otherwise labels it as HTML.
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Retry-After", "5")
	}
	w.ResponseWriter.WriteHeader(status)
}
