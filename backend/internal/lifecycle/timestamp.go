package lifecycle

import (
	"regexp"
	"strings"
	"time"
)

var timestamp = regexp.MustCompile(`^[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt][0-2][0-9]:[0-5][0-9]:([0-5][0-9]|60)(\.[0-9]+)?([Zz]|[+-][0-2][0-9]:[0-5][0-9])$`)

func validTimestamp(value string) bool {
	if !timestamp.MatchString(value) {
		return false
	}
	check := strings.ToUpper(value)
	// Validate leap-second syntax against its preceding second; retain the original spelling.
	leap := check[17:19] == "60"
	if leap {
		check = check[:17] + "59" + check[19:]
	}
	parsed, err := time.Parse(time.RFC3339Nano, check)
	if err != nil {
		return false
	}
	// time.Parse accepts +24:00, which RFC 3339 does not.
	if n := len(check); check[n-1] != 'Z' && check[n-5:n-3] > "23" {
		return false
	}
	if leap {
		utc := parsed.UTC()
		if utc.Hour() != 23 || utc.Minute() != 59 || utc.Add(time.Second).Day() != 1 {
			return false
		}
	}
	return true
}
