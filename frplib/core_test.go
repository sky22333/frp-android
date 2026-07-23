package frplib

import (
	"strings"
	"testing"
)

func withFreshGlobalManager(t *testing.T) {
	t.Helper()
	old := globalManager
	globalManager = newManager()
	t.Cleanup(func() {
		_ = globalManager.stopAll()
		globalManager = old
	})
}

func TestPublicClientAPIsReturnErrorsWithoutCrashing(t *testing.T) {
	withFreshGlobalManager(t)

	if err := StartClient("=\n"); !strings.HasPrefix(err, ErrInvalidToml) {
		t.Fatalf("expected %s, got %q", ErrInvalidToml, err)
	}
	if err := ReloadClient("=\n"); !strings.HasPrefix(err, ErrReloadFailed) {
		t.Fatalf("expected %s, got %q", ErrReloadFailed, err)
	}
	if err := StopClient(); err != "" {
		t.Fatalf("stop should be no-op, got %q", err)
	}
	if IsClientRunning() {
		t.Fatalf("client should not be running")
	}
	if err := StartClientWithID("../bad", ""); !strings.HasPrefix(err, ErrInvalidID) {
		t.Fatalf("expected %s, got %q", ErrInvalidID, err)
	}
	if err := StopClientWithID("../bad"); !strings.HasPrefix(err, ErrInvalidID) {
		t.Fatalf("expected %s, got %q", ErrInvalidID, err)
	}
	if err := ReloadClientWithID("../bad", ""); !strings.HasPrefix(err, ErrInvalidID) {
		t.Fatalf("expected %s, got %q", ErrInvalidID, err)
	}
	if IsClientRunningWithID("../bad") {
		t.Fatalf("invalid id should not be running")
	}
}

func TestPublicServerAPIsReturnErrorsWithoutCrashing(t *testing.T) {
	withFreshGlobalManager(t)

	if err := StartServer("=\n"); !strings.HasPrefix(err, ErrInvalidToml) {
		t.Fatalf("expected %s, got %q", ErrInvalidToml, err)
	}
	if err := ReloadServer("=\n"); !strings.HasPrefix(err, ErrReloadFailed) {
		t.Fatalf("expected %s, got %q", ErrReloadFailed, err)
	}
	if err := StopServer(); err != "" {
		t.Fatalf("stop should be no-op, got %q", err)
	}
	if IsServerRunning() {
		t.Fatalf("server should not be running")
	}
	if err := StartServerWithID("../bad", ""); !strings.HasPrefix(err, ErrInvalidID) {
		t.Fatalf("expected %s, got %q", ErrInvalidID, err)
	}
	if err := StopServerWithID("../bad"); !strings.HasPrefix(err, ErrInvalidID) {
		t.Fatalf("expected %s, got %q", ErrInvalidID, err)
	}
	if err := ReloadServerWithID("../bad", ""); !strings.HasPrefix(err, ErrInvalidID) {
		t.Fatalf("expected %s, got %q", ErrInvalidID, err)
	}
	if IsServerRunningWithID("../bad") {
		t.Fatalf("invalid id should not be running")
	}
}

func TestPublicUtilityAPIs(t *testing.T) {
	withFreshGlobalManager(t)

	if Version() == "" {
		t.Fatalf("version should not be empty")
	}
	if err := StopAll(); err != "" {
		t.Fatalf("stop all should be no-op, got %q", err)
	}
	if got := ListInstances(); got != "" {
		t.Fatalf("expected no instances, got %q", got)
	}
	if errorString(nil) != "" {
		t.Fatalf("nil error should return empty string")
	}
	if got := errorString(newError(ErrInternal, "x")); got != ErrInternal+": x" {
		t.Fatalf("unexpected error string %q", got)
	}
}
