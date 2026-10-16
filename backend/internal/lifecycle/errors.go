package lifecycle

import (
	"errors"
	"fmt"
	"strconv"
	"strings"
)

// Failure contains only safe categories and field names, never submitted values.
type Failure struct {
	Category string
	Field    string
}

func (e *Failure) Error() string { return e.Category }

// Message describes the rule, never the rejected value; Error remains the stable diagnostic category.
func (e *Failure) Message() string {
	switch e.Category {
	case "unauthorized":
		return "A valid bearer credential is required."
	case "forbidden":
		return "This credential does not permit this operation."
	case "not_found":
		return "The alert was not found."
	case "identity_conflict":
		return "This sender event ID already has different content."
	case "body_too_large":
		return fmt.Sprintf("The JSON body must not exceed %s bytes.", formattedLimit(MaxBodyBytes))
	case "invalid_json":
		return "Supply one valid UTF-8 JSON value without duplicate object keys."
	case "invalid_field":
		return validationMessage(e.Field)
	case "backlog_full":
		return "The unfinished-alert limit is reached; retry later."
	default:
		return "Storage is unavailable; retry with the same event ID."
	}
}

func formattedLimit(limit int) string {
	digits := strconv.Itoa(limit)
	var formatted strings.Builder
	for i, digit := range digits {
		if i > 0 && (len(digits)-i)%3 == 0 {
			formatted.WriteByte(',')
		}
		formatted.WriteRune(digit)
	}
	return formatted.String()
}

func failure(category string) error { return &Failure{Category: category} }

func safe(err error) error {
	var failure *Failure
	if errors.As(err, &failure) {
		return failure
	}
	return &Failure{Category: "storage_unavailable"}
}

func safeOrNil(err error) error {
	if err != nil {
		return safe(err)
	}
	return nil
}
