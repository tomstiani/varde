package lifecycle

import (
	"crypto/rand"
	"encoding/base64"
)

func randomID(prefix string) string {
	return prefix + base64.RawURLEncoding.EncodeToString(randomBytes(24))
}

func randomBytes(n int) []byte {
	bytes := make([]byte, n)
	if _, err := rand.Read(bytes); err != nil {
		panic("secure random unavailable")
	}
	return bytes
}
