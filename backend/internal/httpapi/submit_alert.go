package httpapi

import (
	"net/http"

	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

func (h handler) submitAlert(w http.ResponseWriter, r *http.Request) {
	token := bearer(r)
	if err := h.store.Authorize(r.Context(), token, lifecycle.Sender); err != nil {
		writeError(w, err)
		return
	}
	body, err := readJSONBody(w, r)
	if err != nil {
		writeError(w, err)
		return
	}
	alert, created, err := h.store.Submit(r.Context(), token, body)
	if err != nil {
		writeError(w, err)
		return
	}
	status := http.StatusOK
	if created {
		status = http.StatusCreated
	}
	writeJSON(w, status, alert)
}
