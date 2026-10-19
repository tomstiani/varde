package lifecycle

import (
	"bytes"
	"encoding/json"
	"math/big"
)

func equivalentOriginals(previous, next []byte) bool {
	var left, right any
	if !decodeExactJSON(previous, &left) || !decodeExactJSON(next, &right) {
		return false
	}
	return equalJSON(left, right)
}

func decodeExactJSON(body []byte, value *any) bool {
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.UseNumber()
	return decoder.Decode(value) == nil
}

func equalJSON(left, right any) bool {
	switch value := left.(type) {
	case json.Number:
		other, ok := right.(json.Number)
		if !ok {
			return false
		}
		leftDecimal, ok := new(big.Rat).SetString(value.String())
		if !ok {
			return false
		}
		rightDecimal, ok := new(big.Rat).SetString(other.String())
		return ok && leftDecimal.Cmp(rightDecimal) == 0
	case map[string]any:
		other, ok := right.(map[string]any)
		if !ok || len(value) != len(other) {
			return false
		}
		for key, item := range value {
			candidate, exists := other[key]
			if !exists || !equalJSON(item, candidate) {
				return false
			}
		}
		return true
	case []any:
		other, ok := right.([]any)
		if !ok || len(value) != len(other) {
			return false
		}
		for index, item := range value {
			if !equalJSON(item, other[index]) {
				return false
			}
		}
		return true
	default:
		return left == right
	}
}
