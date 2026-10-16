package lifecycle

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"strings"
)

const MaxCursorBytes = 2048

type historyPosition struct {
	Version   int    `json:"v"`
	Watermark int64  `json:"w"`
	Order     string `json:"o"`
	Limit     int    `json:"l"`
	Sequence  int64  `json:"s"`
	Seconds   int64  `json:"t"`
	Nanos     int    `json:"n"`
	ID        string `json:"i"`
}

func historyKey(ctx context.Context, conn *sql.Conn) ([]byte, error) {
	var key []byte
	err := conn.QueryRowContext(ctx, "SELECT cursor_key FROM history_metadata WHERE singleton=1").Scan(&key)
	return key, err
}

func encodeCursor(position historyPosition, key []byte) (string, error) {
	payload, err := json.Marshal(position)
	if err != nil {
		return "", err
	}
	signature := hmac.New(sha256.New, key)
	signature.Write(payload)
	return base64.RawURLEncoding.EncodeToString(payload) + "." + base64.RawURLEncoding.EncodeToString(signature.Sum(nil)), nil
}

func decodeCursor(token string, key []byte) (historyPosition, error) {
	var position historyPosition
	if len(token) > MaxCursorBytes {
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
	signature.Write(payload)
	if !hmac.Equal(received, signature.Sum(nil)) || json.Unmarshal(payload, &position) != nil || position.Version != 1 || position.Watermark < 0 {
		return position, failure("invalid_cursor")
	}
	return position, nil
}
