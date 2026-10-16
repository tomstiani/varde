package main

import (
	"context"
	"os"
	"os/signal"
	"syscall"

	"github.com/tomstiani/varde/backend/internal/app"
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := app.Run(ctx, os.Args[1:], os.Stdout, os.Stderr); err != nil {
		app.ReportFailure(os.Stderr, err)
		os.Exit(1)
	}
}
