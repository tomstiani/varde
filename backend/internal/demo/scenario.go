package demo

import (
	"errors"
	"net/http"
	"reflect"
	"time"

	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

func verifyRestart(binary, database string, sender, reader lifecycle.Credential) error {
	server, err := start(binary, database)
	if err != nil {
		return err
	}
	defer server.stop()
	client := alertClient{
		http:   &http.Client{Transport: &http.Transport{}, Timeout: 3 * time.Second},
		base:   server.base,
		sender: sender.Secret,
		reader: reader.Secret,
	}
	defer client.http.CloseIdleConnections()

	body := []byte(`{"schemaVersion":1,"eventId":"restart-demo","title":"Demo alert","summary":"Synthetic local demonstration","priority":"normal","observedAt":"2026-01-01T12:00:00Z"}`)
	accepted, err := client.submit(body, http.StatusCreated)
	if err != nil {
		return err
	}
	if err = server.stop(); err != nil {
		return err
	}

	server, err = start(binary, database)
	if err != nil {
		return err
	}
	defer server.stop()
	client.base = server.base
	restored, err := client.waitForFallback(accepted.ID)
	if err != nil {
		return err
	}
	if !reflect.DeepEqual(restored.Original, accepted.Original) || restored.AcceptedAt != accepted.AcceptedAt || restored.Presentation.Revision != 1 {
		return errors.New("original or fallback changed across restart")
	}
	duplicate, err := client.submit(body, http.StatusOK)
	if err != nil {
		return err
	}
	if duplicate.ID != accepted.ID {
		return errors.New("duplicate changed alert identity")
	}
	return server.stop()
}
