package app

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net"
	"net/http"
	"sync"
	"time"

	"github.com/tomstiani/varde/backend/internal/httpapi"
	"github.com/tomstiani/varde/backend/internal/lifecycle"
)

func serve(ctx context.Context, store *lifecycle.Lifecycle, address string, out io.Writer) error {
	host, _, err := net.SplitHostPort(address)
	if err != nil || net.ParseIP(host) == nil || !net.ParseIP(host).IsLoopback() {
		return errors.New("loopback_required")
	}
	listener, err := net.Listen("tcp", address)
	if err != nil {
		return errors.New("listen_failed")
	}
	defer listener.Close()
	if err = json.NewEncoder(out).Encode(struct {
		Event   string `json:"event"`
		Address string `json:"address"`
	}{"listening", listener.Addr().String()}); err != nil {
		return errors.New("output_failed")
	}

	server := &http.Server{
		Handler:           httpapi.New(store),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      10 * time.Second,
		IdleTimeout:       30 * time.Second,
		MaxHeaderBytes:    16384,
		ErrorLog:          log.New(io.Discard, "", 0),
	}
	return runServer(ctx, store, server, listener)
}

func runServer(ctx context.Context, store *lifecycle.Lifecycle, server *http.Server, listener net.Listener) error {
	workerCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	finished := make(chan error, 2)
	var workers sync.WaitGroup
	workers.Add(2)
	go func() { defer workers.Done(); finished <- store.Run(workerCtx) }()
	go func() { defer workers.Done(); finished <- server.Serve(listener) }()

	var result error
	select {
	case <-ctx.Done():
	case result = <-finished:
	}
	cancel()
	shutdown, stop := context.WithTimeout(context.Background(), 5*time.Second)
	defer stop()
	if err := server.Shutdown(shutdown); err != nil {
		_ = server.Close()
		if result == nil {
			result = err
		}
	}
	workers.Wait()
	if result == nil || errors.Is(result, context.Canceled) || errors.Is(result, http.ErrServerClosed) {
		return nil
	}
	return errors.New("server_stopped")
}
