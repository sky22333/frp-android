package frplib

import (
	"context"
	"os"
	"regexp"
	"sync"
	"time"
)

const (
	defaultInstanceID = "default"

	instanceTypeClient = "client"
	instanceTypeServer = "server"

	stateRunning  = "running"
	stateStopping = "stopping"
	stateStopped  = "stopped"
	stateFailed   = "failed"

	stopWaitTimeout = 3 * time.Second
)

var validIDPattern = regexp.MustCompile(`^[A-Za-z0-9._-]+$`)

type runningService interface {
	Run(context.Context) error
	Close() error
}

type serviceFactory func(string) (runningService, error)

type instance struct {
	id         string
	typ        string
	state      string
	lastError  string
	configPath string
	cancel     context.CancelFunc
	done       chan struct{}
	service    runningService
}

type manager struct {
	mu        sync.Mutex
	instances map[string]*instance
}

func newManager() *manager {
	return &manager{instances: map[string]*instance{}}
}

func instanceKey(typ, id string) string {
	return typ + ":" + id
}

func validateID(id string) error {
	if id == "" {
		return newError(ErrInvalidID, "instance id is empty")
	}
	if !validIDPattern.MatchString(id) {
		return newError(ErrInvalidID, "instance id %q contains unsupported characters", id)
	}
	return nil
}

func writeConfigTemp(prefix, configToml string) (string, error) {
	dir, err := configTempDir()
	if err != nil {
		return "", err
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", newError(ErrInternal, "create temp config dir failed: %v. On Android, call SetTempDir(context.cacheDir.absolutePath) before starting frp", err)
	}

	file, err := os.CreateTemp(dir, prefix+"-*.toml")
	if err != nil {
		return "", newError(ErrInternal, "create temp config failed: %v. On Android, call SetTempDir(context.cacheDir.absolutePath) before starting frp", err)
	}

	path := file.Name()
	if _, err := file.WriteString(configToml); err != nil {
		_ = file.Close()
		_ = os.Remove(path)
		return "", newError(ErrInternal, "write temp config failed: %v", err)
	}
	if err := file.Close(); err != nil {
		_ = os.Remove(path)
		return "", newError(ErrInternal, "close temp config failed: %v", err)
	}

	return path, nil
}

func removeConfigTemp(path string) {
	if path != "" {
		_ = os.Remove(path)
	}
}

func isDone(done <-chan struct{}) bool {
	select {
	case <-done:
		return true
	default:
		return false
	}
}

// runAlive reports whether the instance goroutine has not finished yet.
func (inst *instance) runAlive() bool {
	return inst != nil && inst.done != nil && !isDone(inst.done)
}

// startBlocker returns an error if a new Start must be refused.
func (inst *instance) startBlocker(typ, id string) error {
	if inst == nil {
		return nil
	}
	if inst.state == stateRunning || inst.state == stateStopping {
		return newError(ErrAlreadyRunning, "%s instance %q is already running", typ, id)
	}
	if inst.runAlive() {
		return newError(ErrStartFailed, "%s instance %q previous run is still shutting down", typ, id)
	}
	return nil
}

func (m *manager) start(typ, id, configToml string, factory serviceFactory) error {
	if err := validateID(id); err != nil {
		return err
	}

	key := instanceKey(typ, id)

	m.mu.Lock()
	if err := m.instances[key].startBlocker(typ, id); err != nil {
		m.mu.Unlock()
		return err
	}
	m.mu.Unlock()

	configPath, err := writeConfigTemp(typ+"-"+id, configToml)
	if err != nil {
		return err
	}

	// Build upstream service outside the manager lock (server.NewService may Listen).
	service, err := factory(configPath)
	if err != nil {
		removeConfigTemp(configPath)
		return err
	}

	if err := m.install(typ, id, configPath, service); err != nil {
		_ = service.Close()
		removeConfigTemp(configPath)
		return err
	}
	return nil
}

// restart installs a service that was already validated. configPath ownership transfers on success.
func (m *manager) restart(typ, id, configPath string, open func() (runningService, error)) error {
	if err := validateID(id); err != nil {
		return err
	}

	key := instanceKey(typ, id)
	m.mu.Lock()
	needsStop := m.instances[key].startBlocker(typ, id) != nil
	m.mu.Unlock()

	if needsStop {
		if err := m.stop(typ, id); err != nil {
			return err
		}
	}

	service, err := open()
	if err != nil {
		return err
	}
	if err := m.install(typ, id, configPath, service); err != nil {
		_ = service.Close()
		return err
	}
	emitLog(id, typ, "info", "reloaded by safe restart")
	return nil
}

func (m *manager) install(typ, id, configPath string, service runningService) error {
	key := instanceKey(typ, id)
	ctx, cancel := context.WithCancel(context.Background())

	m.mu.Lock()
	if err := m.instances[key].startBlocker(typ, id); err != nil {
		m.mu.Unlock()
		cancel()
		return err
	}

	inst := &instance{
		id:         id,
		typ:        typ,
		state:      stateRunning,
		configPath: configPath,
		cancel:     cancel,
		done:       make(chan struct{}),
		service:    service,
	}
	m.instances[key] = inst
	m.mu.Unlock()

	emitLog(id, typ, "info", "started")
	go m.run(key, inst, ctx)
	return nil
}

func (m *manager) run(key string, inst *instance, ctx context.Context) {
	err := inst.service.Run(ctx)

	m.mu.Lock()
	if current, ok := m.instances[key]; ok && current == inst {
		switch {
		case inst.state == stateFailed:
			if err != nil && inst.lastError == "" {
				inst.lastError = err.Error()
			}
		case inst.state == stateStopping:
			inst.state = stateStopped
			inst.lastError = ""
		case err != nil:
			inst.state = stateFailed
			inst.lastError = err.Error()
		default:
			inst.state = stateStopped
			inst.lastError = ""
		}
		removeConfigTemp(inst.configPath)
		inst.configPath = ""
	}
	finalState := inst.state
	close(inst.done)
	m.mu.Unlock()

	if err != nil {
		emitLog(inst.id, inst.typ, "error", err.Error())
	}
	if finalState == stateStopped {
		emitLog(inst.id, inst.typ, "info", "stopped")
	}
}

func (m *manager) stop(typ, id string) error {
	if err := validateID(id); err != nil {
		return err
	}

	key := instanceKey(typ, id)

	m.mu.Lock()
	inst, ok := m.instances[key]
	if !ok || inst.state == stateStopped {
		m.mu.Unlock()
		return nil
	}
	if !inst.runAlive() {
		inst.state = stateStopped
		m.mu.Unlock()
		return nil
	}
	if inst.state == stateStopping {
		done := inst.done
		m.mu.Unlock()
		return m.waitStop(typ, id, inst, done)
	}

	inst.state = stateStopping
	cancel := inst.cancel
	svc := inst.service
	done := inst.done
	m.mu.Unlock()
	emitLog(id, typ, "info", "stopping")

	if cancel != nil {
		cancel()
	}
	var closeErr error
	if svc != nil {
		closeErr = svc.Close()
	}

	if err := m.waitStop(typ, id, inst, done); err != nil {
		return err
	}
	if closeErr != nil {
		return newError(ErrStopFailed, "stop %s instance %q failed: %v", typ, id, closeErr)
	}
	return nil
}

func (m *manager) waitStop(typ, id string, inst *instance, done <-chan struct{}) error {
	if done == nil {
		return nil
	}
	select {
	case <-done:
		return nil
	case <-time.After(stopWaitTimeout):
		m.mu.Lock()
		if !isDone(inst.done) {
			inst.state = stateFailed
			inst.lastError = "stop timeout"
		}
		m.mu.Unlock()
		return newError(ErrStopFailed, "stop %s instance %q timed out", typ, id)
	}
}

func (m *manager) stopAll() error {
	m.mu.Lock()
	items := make([]*instance, 0, len(m.instances))
	for _, inst := range m.instances {
		if inst.state == stateStopped {
			continue
		}
		if !inst.runAlive() {
			inst.state = stateStopped
			continue
		}
		items = append(items, inst)
	}
	m.mu.Unlock()

	var last error
	for _, inst := range items {
		if err := m.stop(inst.typ, inst.id); err != nil {
			last = err
		}
	}
	return last
}

func (m *manager) listInstances() string {
	m.mu.Lock()
	defer m.mu.Unlock()

	out := ""
	first := true
	for _, inst := range m.instances {
		if !first {
			out += "\n"
		}
		first = false
		out += inst.typ + ":" + inst.id + ":" + inst.state
		if inst.lastError != "" {
			out += ":" + inst.lastError
		}
	}
	return out
}

func (m *manager) isRunning(typ, id string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	inst, ok := m.instances[instanceKey(typ, id)]
	return ok && inst.state == stateRunning
}
