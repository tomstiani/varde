package lifecycle

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"strings"
)

type changePosition struct {
	Version int    `json:"v"`
	After   int64  `json:"a"`
	Horizon *int64 `json:"h,omitempty"`
	Limit   int    `json:"l,omitempty"`
}

func encodeChangesCursor(position changePosition, key []byte) (string, error) {
	payload, err := json.Marshal(position)
	if err != nil {
		return "", err
	}
	signature := hmac.New(sha256.New, key)
	signature.Write([]byte("changes/v1\x00"))
	signature.Write(payload)
	return base64.RawURLEncoding.EncodeToString(payload) + "." + base64.RawURLEncoding.EncodeToString(signature.Sum(nil)), nil
}

func decodeChangesCursor(token string, key []byte) (changePosition, error) {
	var position changePosition
	if len(token) == 0 || len(token) > MaxCursorBytes {
		return position, failure("invalid_cursor")
	}
	parts := strings.Split(token, ".")
	if len(parts) != 2 {
		return position, failure("invalid_cursor")
	}
	payload, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		return position, failure("invalid_cursor")
	}
	received, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return position, failure("invalid_cursor")
	}
	signature := hmac.New(sha256.New, key)
	signature.Write([]byte("changes/v1\x00"))
	signature.Write(payload)
	if !hmac.Equal(received, signature.Sum(nil)) || json.Unmarshal(payload, &position) != nil || position.Version != 1 || position.After < 0 {
		return position, failure("invalid_cursor")
	}
	if position.Horizon != nil && (*position.Horizon < position.After || position.Limit < 1 || position.Limit > 100) {
		return position, failure("invalid_cursor")
	}
	if position.Horizon == nil && position.Limit != 0 {
		return position, failure("invalid_cursor")
	}
	return position, nil
}
