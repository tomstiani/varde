package httpapi

import "net/http"

func (h handler) readAlert(w http.ResponseWriter, r *http.Request) {
	alert, err := h.store.Read(r.Context(), bearer(r), r.PathValue("alertId"))
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, alert)
}
