package demo

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"time"

	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

type alertClient struct {
	http   *http.Client
	base   string
	sender string
	reader string
}

func (c *alertClient) submit(body []byte, expectedStatus int) (lifecycle.Alert, error) {
	return c.request("POST", "/v1/alerts", c.sender, body, expectedStatus)
}

func (c *alertClient) waitForFallback(id string) (lifecycle.Alert, error) {
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		alert, err := c.request("GET", "/v1/alerts/"+id, c.reader, nil, http.StatusOK)
		if err != nil {
			return lifecycle.Alert{}, err
		}
		if alert.Presentation.State == "fallback" {
			return alert, nil
		}
		time.Sleep(20 * time.Millisecond)
	}
	return lifecycle.Alert{}, errors.New("text fallback timed out")
}

func (c *alertClient) request(method, path, token string, body []byte, expectedStatus int) (lifecycle.Alert, error) {
	var alert lifecycle.Alert
	request, err := http.NewRequest(method, c.base+path, bytes.NewReader(body))
	if err != nil {
		return alert, errors.New("request setup failed")
	}
	request.Header.Set("Authorization", "Bearer "+token)
	request.Header.Set("Content-Type", "application/json")
	response, err := c.http.Do(request)
	if err != nil {
		return alert, errors.New("local request failed")
	}
	defer response.Body.Close()
	if response.StatusCode != expectedStatus {
		return alert, fmt.Errorf("unexpected status: %d (wanted %d)", response.StatusCode, expectedStatus)
	}
	if err = json.NewDecoder(io.LimitReader(response.Body, 1<<20)).Decode(&alert); err != nil {
		return alert, errors.New("invalid alert response")
	}
	return alert, nil
}
