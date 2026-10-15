package lifecycle

import (
	"bytes"
	"encoding/json"
	"io"
	"strconv"
	"unicode/utf8"
)

func decodeOriginalJSON(body []byte) (map[string]json.RawMessage, error) {
	if len(body) > MaxBodyBytes {
		return nil, failure("body_too_large")
	}
	if !utf8.Valid(body) || !validSurrogates(body) {
		return nil, failure("invalid_json")
	}
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.UseNumber()
	if err := walkJSON(decoder); err != nil {
		return nil, failure("invalid_json")
	}
	if _, err := decoder.Token(); err != io.EOF {
		return nil, failure("invalid_json")
	}
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(body, &raw); err != nil || raw == nil {
		return nil, failure("invalid_field")
	}
	return raw, nil
}

// Token traversal detects duplicate keys before decoding can discard them.
func walkJSON(decoder *json.Decoder) error {
	token, err := decoder.Token()
	if err != nil {
		return err
	}
	delimiter, isDelimiter := token.(json.Delim)
	if !isDelimiter {
		return nil
	}
	if delimiter != '{' && delimiter != '[' {
		return io.ErrUnexpectedEOF
	}

	keys := map[string]bool{}
	for decoder.More() {
		if delimiter == '{' {
			key, err := decoder.Token()
			if err != nil {
				return err
			}
			name, isString := key.(string)
			if !isString || keys[name] {
				return io.ErrUnexpectedEOF
			}
			keys[name] = true
		}
		if err := walkJSON(decoder); err != nil {
			return err
		}
	}
	_, err = decoder.Token()
	return err
}

// encoding/json replaces lone UTF-16 surrogates; reject them instead of rewriting originals.
func validSurrogates(body []byte) bool {
	for i := 0; i < len(body); i++ {
		if body[i] != '\\' {
			continue
		}
		i++
		if i >= len(body) {
			return false
		}
		if body[i] != 'u' {
			continue
		}
		if i+4 >= len(body) {
			return false
		}
		value, err := strconv.ParseUint(string(body[i+1:i+5]), 16, 16)
		if err != nil {
			return false
		}
		i += 4
		if value >= 0xdc00 && value <= 0xdfff {
			return false
		}
		if value < 0xd800 || value > 0xdbff {
			continue
		}
		if i+6 >= len(body) || body[i+1] != '\\' || body[i+2] != 'u' {
			return false
		}
		low, err := strconv.ParseUint(string(body[i+3:i+7]), 16, 16)
		if err != nil || low < 0xdc00 || low > 0xdfff {
			return false
		}
		i += 6
	}
	return true
}
