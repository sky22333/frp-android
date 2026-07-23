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
)

var validIDPattern = regexp.MustCompile(`^[A-Za-z0-9._-]+$`)

type runningService interface {
	Run(context.Context) error
	Close() error
}

type serviceFactory func(string) (runningService, error)

type configValidator func(string) error

type instance struct {
	id         string
	typ        string
	state      string
	lastError  string
	configPath string
	cancel     context.CancelFunc
	done       chan struct{}
	service    runningService
	stopping   bool
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

func (m *manager) start(typ, id, configToml string, factory serviceFactory) error {
	if err := validateID(id); err != nil {
		return err
	}

	key := instanceKey(typ, id)

	m.mu.Lock()
	if current, ok := m.instances[key]; ok && current.state != stateStopped && current.state != stateFailed {
		m.mu.Unlock()
		return newError(ErrAlreadyRunning, "%s instance %q is already running", typ, id)
	}
	m.mu.Unlock()

	configPath, err := writeConfigTemp(typ+"-"+id, configToml)
	if err != nil {
		return err
	}

	ctx, cancel := context.WithCancel(context.Background())

	m.mu.Lock()
	if current, ok := m.instances[key]; ok && current.state != stateStopped && current.state != stateFailed {
		m.mu.Unlock()
		cancel()
		removeConfigTemp(configPath)
		return newError(ErrAlreadyRunning, "%s instance %q is already running", typ, id)
	}
	service, err := factory(configPath)
	if err != nil {
		m.mu.Unlock()
		cancel()
		removeConfigTemp(configPath)
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
	finalState := stateStopped

	m.mu.Lock()
	if current, ok := m.instances[key]; ok && current == inst {
		switch {
		case inst.state == stateFailed:
		case inst.stopping:
			inst.state = stateStopped
			inst.lastError = ""
		case err != nil:
			inst.state = stateFailed
			inst.lastError = err.Error()
		default:
			inst.state = stateStopped
			inst.lastError = ""
		}
		finalState = inst.state
		removeConfigTemp(inst.configPath)
		inst.configPath = ""
	}
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
	if !ok || inst.state == stateStopped || inst.state == stateStopping || inst.state == stateFailed {
		m.mu.Unlock()
		return nil
	}
	inst.state = stateStopping
	inst.stopping = true
	m.mu.Unlock()
	emitLog(id, typ, "info", "stopping")

	if inst.cancel != nil {
		inst.cancel()
	}

	var closeErr error
	if inst.service != nil {
		closeErr = inst.service.Close()
	}

	select {
	case <-inst.done:
	case <-time.After(3 * time.Second):
		m.mu.Lock()
		inst.state = stateFailed
		inst.lastError = "stop timeout"
		m.mu.Unlock()
		return newError(ErrStopFailed, "stop %s instance %q timed out", typ, id)
	}
	if closeErr != nil {
		return newError(ErrStopFailed, "stop %s instance %q failed: %v", typ, id, closeErr)
	}

	return nil
}

func (m *manager) reload(typ, id, configToml string, validate configValidator, factory serviceFactory) error {
	if err := validateID(id); err != nil {
		return err
	}

	key := instanceKey(typ, id)

	m.mu.Lock()
	old, running := m.instances[key]
	running = running && old.state != stateStopped && old.state != stateFailed
	m.mu.Unlock()

	configPath, err := writeConfigTemp(typ+"-"+id+"-reload", configToml)
	if err != nil {
		return err
	}

	if err := validate(configPath); err != nil {
		removeConfigTemp(configPath)
		return newError(ErrReloadFailed, "%v", err)
	}
	removeConfigTemp(configPath)

	if running {
		if err := m.stop(typ, id); err != nil {
			return newError(ErrReloadFailed, "%v", err)
		}
	}

	if err := m.start(typ, id, configToml, factory); err != nil {
		return newError(ErrReloadFailed, "%v", err)
	}
	emitLog(id, typ, "info", "reloaded by safe restart")
	return nil
}

func (m *manager) stopAll() error {
	m.mu.Lock()
	items := make([]*instance, 0, len(m.instances))
	for _, inst := range m.instances {
		if inst.state != stateStopped && inst.state != stateFailed {
			items = append(items, inst)
		}
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
