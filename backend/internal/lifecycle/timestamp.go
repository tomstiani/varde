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
		if utc.Hour() != 23 || utc.Minute() != 59 || !hasLeapSecond(utc) {
			return false
		}
	}
	return true
}

func hasLeapSecond(utc time.Time) bool {
	// IERS insertion dates; future leap seconds require an announced date here.
	switch utc.Format("2006-01-02") {
	case "1972-06-30", "1972-12-31", "1973-12-31", "1974-12-31",
		"1975-12-31", "1976-12-31", "1977-12-31", "1978-12-31",
		"1979-12-31", "1981-06-30", "1982-06-30", "1983-06-30",
		"1985-06-30", "1987-12-31", "1989-12-31", "1990-12-31",
		"1992-06-30", "1993-06-30", "1994-06-30", "1995-12-31",
		"1997-06-30", "1998-12-31", "2005-12-31", "2008-12-31",
		"2012-06-30", "2015-06-30", "2016-12-31":
		return true
	default:
		return false
	}
}
