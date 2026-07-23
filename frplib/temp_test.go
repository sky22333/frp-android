package frplib

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func resetTempDirForTest(t *testing.T) {
	t.Helper()
	tempDirState.Lock()
	old := tempDirState.dir
	tempDirState.dir = ""
	tempDirState.Unlock()
	t.Cleanup(func() {
		tempDirState.Lock()
		tempDirState.dir = old
		tempDirState.Unlock()
	})
}

func TestSetTempDirUsesPrivateFrplibSubdir(t *testing.T) {
	resetTempDirForTest(t)
	base := t.TempDir()

	if err := setTempDir(base); err != nil {
		t.Fatalf("set temp dir failed: %v", err)
	}
	path, err := writeConfigTemp("client-a", "x=1")
	if err != nil {
		t.Fatalf("write temp config failed: %v", err)
	}
	defer removeConfigTemp(path)

	wantDir := filepath.Join(base, "frplib")
	if !strings.HasPrefix(path, wantDir+string(os.PathSeparator)) {
		t.Fatalf("expected temp file under %q, got %q", wantDir, path)
	}
	if _, err := os.Stat(path); err != nil {
		t.Fatalf("temp config should exist: %v", err)
	}
}

func TestSetTempDirRejectsEmptyDir(t *testing.T) {
	if got := SetTempDir(""); !strings.HasPrefix(got, ErrInvalidTempDir) {
		t.Fatalf("expected %s, got %q", ErrInvalidTempDir, got)
	}
}
