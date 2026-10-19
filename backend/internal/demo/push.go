package demo

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"time"

	"github.com/tomstiani/varde/backend/internal/fakepush"
	"github.com/tomstiani/varde/backend/internal/httpapi"
	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

func verifyFakePush(path string) error {
	store, err := lifecycle.Open(path)
	if err != nil {
		return err
	}
	defer func() {
		if store != nil {
			store.Close()
		}
	}()
	ctx := context.Background()
	sender, err := demoCredential(ctx, store, lifecycle.Sender)
	if err != nil {
		return err
	}
	device, err := demoCredential(ctx, store, lifecycle.Installation)
	if err != nil {
		return err
	}
	server := httptest.NewServer(httpapi.New(store))
	defer func() { server.Close() }()
	client := alertClient{http: server.Client(), base: server.URL, sender: sender.Secret, reader: device.Secret}
	client.http.Timeout = 3 * time.Second
	body := []byte(`{"schemaVersion":1,"eventId":"silent-history","title":"Synthetic title","summary":"Synthetic summary","priority":"normal","observedAt":"2026-01-01T00:00:00Z"}`)
	if _, err := client.submit(body, http.StatusCreated); err != nil {
		return err
	}
	first, err := demoRegistration(&client)
	if err != nil {
		return err
	}
	future := []byte(strings.Replace(string(body), "silent-history", "future-arrival", 1))
	alert, err := client.submit(future, http.StatusCreated)
	if err != nil {
		return err
	}
	if _, err := client.submit(future, http.StatusOK); err != nil {
		return err
	}
	server.Close()
	if err := store.Close(); err != nil {
		return err
	}
	store, err = lifecycle.Open(path)
	if err != nil {
		return err
	}
	server = httptest.NewServer(httpapi.New(store))
	client.http = server.Client()
	client.base = server.URL
	client.http.Timeout = 3 * time.Second
	replay, err := demoRegistration(&client)
	if err != nil {
		return err
	}
	if replay.BaselineCursor == nil || first.BaselineCursor == nil || *replay.BaselineCursor != *first.BaselineCursor {
		return errors.New("registration replay changed baseline")
	}
	now := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	provider := fakepush.New(lifecycle.PushSendResult{Outcome: lifecycle.PushTransient}, lifecycle.PushSendResult{Outcome: lifecycle.PushDelivered})
	dispatcher := store.NewPushDispatcher(provider, lifecycle.PushDispatchOptions{Now: func() time.Time { return now }, RetryJitter: func(delay time.Duration) time.Duration { return delay }})
	report, err := dispatcher.DispatchOne(ctx)
	if err != nil || report.Status != "retry_scheduled" {
		return errors.New("fake transient attempt failed")
	}
	now = now.Add(5 * time.Second)
	report, err = dispatcher.DispatchOne(ctx)
	if err != nil || report.Status != "delivered" {
		return errors.New("fake retry did not complete")
	}
	messages := provider.Messages()
	if len(messages) != 2 || messages[0].Data != messages[1].Data || messages[0].Data.AlertID != alert.ID {
		return errors.New("fake routing or retry changed")
	}
	report, err = dispatcher.DispatchOne(ctx)
	if err != nil || report.Status != "idle" {
		return errors.New("history or duplicate scheduled extra work")
	}
	return nil
}

func demoCredential(ctx context.Context, store *lifecycle.Lifecycle, role lifecycle.Role) (lifecycle.Credential, error) {
	identity, err := store.CreateIdentity(ctx, role, "synthetic-demo")
	if err != nil {
		return lifecycle.Credential{}, err
	}
	return store.IssueCredential(ctx, identity.ID)
}

func demoRegistration(client *alertClient) (lifecycle.PushRegistration, error) {
	var registration lifecycle.PushRegistration
	body := []byte(`{"registrationId":"11111111111111111111111111111111","provider":"fcm","destination":"synthetic-private-destination"}`)
	request, err := http.NewRequest("PUT", client.base+"/v1/installation/push", bytes.NewReader(body))
	if err != nil {
		return registration, errors.New("registration request setup failed")
	}
	request.Header.Set("Authorization", "Bearer "+client.reader)
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("If-Match", `"0"`)
	request.Header.Set("Idempotency-Key", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
	response, err := client.http.Do(request)
	if err != nil {
		return registration, errors.New("registration request failed")
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK || response.Header.Get("ETag") != `"1"` {
		return registration, errors.New("unexpected registration acknowledgement")
	}
	if err := json.NewDecoder(response.Body).Decode(&registration); err != nil {
		return registration, errors.New("invalid registration response")
	}
	return registration, nil
}
