package main

import (
	"fmt"
	"os"

	"github.com/tomstiani/varde/backend/internal/demo"
)

func main() {
	if err := demo.Run(os.Stdout); err != nil {
		fmt.Fprintln(os.Stderr, "demo:", err)
		os.Exit(1)
	}
}
