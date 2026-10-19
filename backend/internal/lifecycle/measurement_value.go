package lifecycle

import (
	"encoding/json"
	"regexp"
	"strconv"
	"strings"
	"time"
)

var typedTime = regexp.MustCompile(`^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?(Z|[+-]\d{2}:\d{2})$`)

func validTypedTime(value string) bool {
	if !typedTime.MatchString(value) || strings.HasSuffix(value, "-00:00") {
		return false
	}
	// time.Parse accepts out-of-range offset hours/minutes in some spellings.
	if value[len(value)-1] != 'Z' {
		hour, _ := strconv.Atoi(value[len(value)-5 : len(value)-3])
		minute, _ := strconv.Atoi(value[len(value)-2:])
		if hour > 23 || minute > 59 {
			return false
		}
	}
	_, err := time.Parse(time.RFC3339Nano, value)
	return err == nil
}

func validDecimal(token string) bool {
	if len(token) == 0 || len(token) > 64 || token[0] == '"' {
		return false
	}
	var number json.Number
	if json.Unmarshal([]byte(token), &number) != nil || number.String() != token {
		return false
	}
	parts := strings.FieldsFunc(strings.ToLower(strings.TrimPrefix(token, "-")), func(r rune) bool { return r == 'e' })
	exponent := 0
	if len(parts) == 2 {
		var err error
		exponent, err = strconv.Atoi(parts[1])
		if err != nil || exponent < -100 || exponent > 100 {
			return false
		}
	}
	mantissa := parts[0]
	fraction := 0
	if point := strings.IndexByte(mantissa, '.'); point >= 0 {
		fraction = len(mantissa) - point - 1
	}
	digits := strings.TrimLeft(strings.ReplaceAll(mantissa, ".", ""), "0")
	if len(digits) > 34 {
		return false
	}
	if digits != "" {
		adjusted := exponent - fraction + len(digits) - 1
		if adjusted < -100 || adjusted > 100 {
			return false
		}
	}
	return true
}
