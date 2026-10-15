package demo

import (
	"encoding/json"
	"errors"
	"os"
	"os/exec"
	"time"
)

type process struct {
	cmd     *exec.Cmd
	base    string
	stopped bool
}

func start(binary, database string) (*process, error) {
	command := exec.Command(binary, "-db", database, "serve", "-listen", "127.0.0.1:0")
	stdout, err := command.StdoutPipe()
	if err != nil {
		return nil, errors.New("server output setup failed")
	}
	if err = command.Start(); err != nil {
		return nil, errors.New("server start failed")
	}
	server := &process{cmd: command}
	ready := make(chan string, 1)
	go func() {
		var event struct {
			Address string `json:"address"`
		}
		_ = json.NewDecoder(stdout).Decode(&event)
		ready <- event.Address
	}()
	select {
	case address := <-ready:
		if address != "" {
			server.base = "http://" + address
			return server, nil
		}
	case <-time.After(10 * time.Second):
	}
	_ = server.stop()
	return nil, errors.New("server readiness failed")
}

func (p *process) stop() error {
	if p.stopped {
		return nil
	}
	p.stopped = true
	if err := p.cmd.Process.Signal(os.Interrupt); err != nil {
		_ = p.cmd.Process.Kill()
	}
	done := make(chan error, 1)
	go func() { done <- p.cmd.Wait() }()
	select {
	case err := <-done:
		if err != nil {
			return errors.New("server shutdown failed")
		}
		return nil
	case <-time.After(7 * time.Second):
		_ = p.cmd.Process.Kill()
		<-done
		return errors.New("server shutdown timed out")
	}
}
