package frplib

import (
	"context"
	"errors"
	"strings"
	"sync"
	"testing"

	frplog "github.com/fatedier/frp/pkg/util/log"
)

type fakeService struct {
	done   chan struct{}
	exited chan struct{}
	once   sync.Once
	closed bool
	runErr error
}

func newFakeService() *fakeService {
	return &fakeService{done: make(chan struct{}), exited: make(chan struct{})}
}

func (f *fakeService) Run(ctx context.Context) error {
	defer close(f.exited)
	select {
	case <-ctx.Done():
		return nil
	case <-f.done:
		return f.runErr
	}
}

func (f *fakeService) Close() error {
	f.closed = true
	f.once.Do(func() {
		close(f.done)
	})
	return nil
}

func TestRepeatedStartReturnsAlreadyRunning(t *testing.T) {
	m := newManager()
	factory := func(string) (runningService, error) {
		return newFakeService(), nil
	}

	if err := m.start(instanceTypeClient, "a", "x=1", factory); err != nil {
		t.Fatalf("first start failed: %v", err)
	}
	if err := m.start(instanceTypeClient, "a", "x=1", factory); err == nil {
		t.Fatalf("second start should fail")
	} else if got := err.Error(); !strings.HasPrefix(got, ErrAlreadyRunning) {
		t.Fatalf("expected %s, got %q", ErrAlreadyRunning, got)
	}
	_ = m.stop(instanceTypeClient, "a")
}

func TestRepeatedStopIsNoop(t *testing.T) {
	m := newManager()
	factory := func(string) (runningService, error) {
		return newFakeService(), nil
	}

	if err := m.start(instanceTypeClient, "a", "x=1", factory); err != nil {
		t.Fatalf("start failed: %v", err)
	}
	if err := m.stop(instanceTypeClient, "a"); err != nil {
		t.Fatalf("first stop failed: %v", err)
	}
	if err := m.stop(instanceTypeClient, "a"); err != nil {
		t.Fatalf("second stop should be no-op: %v", err)
	}
}

func TestRunErrorMarksInstanceFailed(t *testing.T) {
	m := newManager()
	svc := newFakeService()
	svc.runErr = errors.New("boom")
	factory := func(string) (runningService, error) {
		return svc, nil
	}

	if err := m.start(instanceTypeClient, "a", "x=1", factory); err != nil {
		t.Fatalf("start failed: %v", err)
	}
	if err := svc.Close(); err != nil {
		t.Fatalf("close fake service: %v", err)
	}
	<-svc.exited

	m.mu.Lock()
	inst := m.instances[instanceKey(instanceTypeClient, "a")]
	state := inst.state
	lastError := inst.lastError
	m.mu.Unlock()

	if state != stateFailed {
		t.Fatalf("expected failed, got %q", state)
	}
	if lastError != "boom" {
		t.Fatalf("expected run error, got %q", lastError)
	}
}

func TestReloadValidationFailureKeepsOldInstance(t *testing.T) {
	m := newManager()
	svc := newFakeService()
	factory := func(string) (runningService, error) {
		return svc, nil
	}
	validate := func(string) error {
		return newError(ErrInvalidToml, "bad config")
	}

	if err := m.start(instanceTypeClient, "a", "x=1", factory); err != nil {
		t.Fatalf("start failed: %v", err)
	}
	err := m.reload(instanceTypeClient, "a", "bad", validate, factory)
	if err == nil {
		t.Fatalf("reload should fail")
	}
	if !strings.HasPrefix(err.Error(), ErrReloadFailed) {
		t.Fatalf("expected %s, got %q", ErrReloadFailed, err.Error())
	}
	if svc.closed {
		t.Fatalf("old service should still be running")
	}
	if !m.isRunning(instanceTypeClient, "a") {
		t.Fatalf("old instance should remain running")
	}

	if err := m.stop(instanceTypeClient, "a"); err != nil {
		t.Fatalf("stop failed: %v", err)
	}
}

func TestReloadSuccessReplacesInstance(t *testing.T) {
	m := newManager()
	oldSvc := newFakeService()
	newSvc := newFakeService()
	services := []*fakeService{oldSvc, newSvc}
	factory := func(string) (runningService, error) {
		svc := services[0]
		services = services[1:]
		return svc, nil
	}
	validate := func(string) error {
		return nil
	}

	if err := m.start(instanceTypeClient, "a", "x=1", factory); err != nil {
		t.Fatalf("start failed: %v", err)
	}
	if err := m.reload(instanceTypeClient, "a", "x=2", validate, factory); err != nil {
		t.Fatalf("reload failed: %v", err)
	}
	if !oldSvc.closed {
		t.Fatalf("old service should be closed")
	}
	if newSvc.closed {
		t.Fatalf("new service should be running")
	}
	if !m.isRunning(instanceTypeClient, "a") {
		t.Fatalf("new instance should be running")
	}

	if err := m.stop(instanceTypeClient, "a"); err != nil {
		t.Fatalf("stop failed: %v", err)
	}
}

func TestStartFactoryFailureDoesNotRegisterInstance(t *testing.T) {
	m := newManager()
	factory := func(string) (runningService, error) {
		return nil, newError(ErrStartFailed, "factory failed")
	}

	if err := m.start(instanceTypeClient, "a", "x=1", factory); err == nil {
		t.Fatalf("start should fail")
	}
	if got := m.listInstances(); got != "" {
		t.Fatalf("failed instance should not be registered, got %q", got)
	}
}

func TestStopAllStopsRunningInstances(t *testing.T) {
	m := newManager()
	services := map[string]*fakeService{}
	factory := func(configPath string) (runningService, error) {
		svc := newFakeService()
		services[configPath] = svc
		return svc, nil
	}

	if err := m.start(instanceTypeClient, "a", "x=1", factory); err != nil {
		t.Fatalf("start client failed: %v", err)
	}
	if err := m.start(instanceTypeServer, "b", "x=1", factory); err != nil {
		t.Fatalf("start server failed: %v", err)
	}
	if err := m.stopAll(); err != nil {
		t.Fatalf("stopAll failed: %v", err)
	}
	if m.isRunning(instanceTypeClient, "a") || m.isRunning(instanceTypeServer, "b") {
		t.Fatalf("instances should be stopped")
	}
	for _, svc := range services {
		if !svc.closed {
			t.Fatalf("all services should be closed")
		}
	}
}

func TestListInstancesIncludesLastError(t *testing.T) {
	m := newManager()
	svc := newFakeService()
	svc.runErr = errors.New("boom")
	factory := func(string) (runningService, error) {
		return svc, nil
	}

	if err := m.start(instanceTypeClient, "a", "x=1", factory); err != nil {
		t.Fatalf("start failed: %v", err)
	}
	if err := svc.Close(); err != nil {
		t.Fatalf("close fake service: %v", err)
	}
	<-svc.exited

	got := m.listInstances()
	want := "client:a:failed:boom"
	if got != want {
		t.Fatalf("expected %q, got %q", want, got)
	}
}

type testLogCallback struct {
	mu   sync.Mutex
	logs []string
}

func (c *testLogCallback) OnLog(instanceID string, typ string, level string, message string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.logs = append(c.logs, instanceID+":"+typ+":"+level+":"+message)
}

func (c *testLogCallback) contains(part string) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	for _, log := range c.logs {
		if strings.Contains(log, part) {
			return true
		}
	}
	return false
}

func TestLogCallbackReceivesLifecycleAndFrpLogs(t *testing.T) {
	callback := &testLogCallback{}
	SetLogCallback(callback)
	defer SetLogCallback(nil)

	emitLog("a", instanceTypeClient, "info", " started ")
	frplog.Infof("official log")

	if !callback.contains("a:client:info:started") {
		t.Fatalf("missing lifecycle log: %#v", callback.logs)
	}
	if !callback.contains(":frp:info:") || !callback.contains("official log") {
		t.Fatalf("missing frp log: %#v", callback.logs)
	}
}

func TestAndroidLogWriterWrite(t *testing.T) {
	callback := &testLogCallback{}
	SetLogCallback(callback)
	defer SetLogCallback(nil)

	writer := androidLogWriter{}
	n, err := writer.Write([]byte("writer log\n"))
	if err != nil {
		t.Fatalf("write failed: %v", err)
	}
	if n != len("writer log\n") {
		t.Fatalf("unexpected byte count %d", n)
	}
	if !callback.contains(":frp:info:writer log") {
		t.Fatalf("missing writer log: %#v", callback.logs)
	}
}

func TestInvalidID(t *testing.T) {
	if err := validateID("../bad"); err == nil {
		t.Fatalf("invalid id should fail")
	}
	if err := validateID(""); err == nil {
		t.Fatalf("empty id should fail")
	}
}
