package lifecycle

import (
	"encoding/json"
	"fmt"
	"regexp"
	"slices"
	"strings"
	"unicode"
	"unicode/utf8"
)

var identifier = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9_-]{0,63}$`)

func validateMeasurements(original *Original) error {
	if len(original.Data) > 8 {
		return invalidField("data")
	}
	if original.DataDefaults != nil {
		var defaults map[string]json.RawMessage
		if json.Unmarshal(original.DataDefaults, &defaults) != nil || defaults == nil {
			return invalidField("dataDefaults")
		}
		if err := validateMetadata(defaults, "dataDefaults"); err != nil {
			return err
		}
	}
	ids := map[string]bool{}
	for index, entry := range original.Data {
		field := fmt.Sprintf("data[%d]", index)
		id, err := validateStandaloneMeasurement(entry, field)
		if err != nil {
			return err
		}
		if id != "" {
			if ids[id] {
				return invalidField(field + ".id")
			}
			ids[id] = true
		}
	}
	return nil
}

func validateStandaloneMeasurement(entry json.RawMessage, field string) (string, error) {
	var values map[string]json.RawMessage
	if json.Unmarshal(entry, &values) != nil || values == nil {
		return "", invalidField(field)
	}
	var kind string
	if json.Unmarshal(values["type"], &kind) != nil || kind != "measurement" {
		return "", invalidField(field + ".type")
	}
	// Reject staged fields explicitly, even if empty or null.
	if _, exists := values["samples"]; exists {
		return "", invalidField(field + ".samples")
	}
	allowed := []string{"type", "label", "id", "source", "resource", "collector", "unit", "value", "measuredAt"}
	for name := range values {
		if !slices.Contains(allowed, name) {
			return "", invalidField(field)
		}
	}
	if !validLabel(values["label"], 128, false) {
		return "", invalidField(field + ".label")
	}
	var id string
	if value, exists := values["id"]; exists {
		if json.Unmarshal(value, &id) != nil || !identifier.MatchString(id) {
			return "", invalidField(field + ".id")
		}
	}
	for _, name := range []string{"source", "resource", "collector", "unit"} {
		if value, exists := values[name]; exists {
			limit := 128
			if name == "unit" {
				limit = 32
			}
			if !validLabel(value, limit, true) {
				return "", invalidField(field + "." + name)
			}
		}
	}
	value, exists := values["value"]
	if !exists || (string(value) != "null" && !validDecimal(string(value))) {
		return "", invalidField(field + ".value")
	}
	if value, exists := values["measuredAt"]; exists {
		var spelling string
		if json.Unmarshal(value, &spelling) != nil || !validTypedTime(spelling) {
			return "", invalidField(field + ".measuredAt")
		}
	}
	return id, nil
}

func validateMetadata(values map[string]json.RawMessage, field string) error {
	for name, value := range values {
		if name != "source" && name != "resource" && name != "collector" {
			return invalidField(field)
		}
		if !validLabel(value, 128, true) {
			return invalidField(field + "." + name)
		}
	}
	return nil
}

func measurementValidationMessage(field string) string {
	if field == "dataDefaults" {
		return "dataDefaults must be an object containing only optional nullable source, resource and collector labels."
	}
	suffix := field[strings.LastIndex(field, ".")+1:]
	switch suffix {
	case "samples":
		return "Measurement samples are not supported yet; submit a standalone value without samples."
	case "type":
		return "type must be measurement; records and sequences are not supported yet."
	case "value":
		return "value is required and must be null or an exact decimal number: at most 64 bytes, 34 significant digits, and explicit and adjusted nonzero exponents from -100 through 100."
	case "measuredAt":
		return "measuredAt must be a calendar-valid RFC 3339 timestamp with uppercase T/Z, a known explicit offset, at most 9 fractional digits and no leap second."
	case "id":
		return "id must be unique within the alert: 1–64 ASCII letters, digits, underscores or hyphens, beginning with a letter."
	case "unit":
		return "unit must be null or nonblank single-line text of at most 32 characters without control characters."
	case "label", "source", "resource", "collector":
		return "Labels must be nonblank single-line text of at most 128 characters without control characters; provenance labels may also be null."
	default:
		return "Each data entry must be a closed standalone measurement object with type, label and value; samples and unknown fields are not supported."
	}
}

func validLabel(raw json.RawMessage, limit int, nullable bool) bool {
	if nullable && string(raw) == "null" {
		return true
	}
	var value string
	if len(raw) == 0 || raw[0] != '"' || json.Unmarshal(raw, &value) != nil || strings.TrimSpace(value) == "" || utf8.RuneCountInString(value) > limit {
		return false
	}
	for _, character := range value {
		if unicode.IsControl(character) || character == '\u2028' || character == '\u2029' {
			return false
		}
	}
	return true
}
