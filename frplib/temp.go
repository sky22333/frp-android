package frplib

import (
	"os"
	"path/filepath"
	"sync"
)

var tempDirState = struct {
	sync.RWMutex
	dir string
}{}

func SetTempDir(dir string) string {
	return errorString(setTempDir(dir))
}

func setTempDir(dir string) error {
	if dir == "" {
		return newError(ErrInvalidTempDir, "temp dir is empty")
	}

	abs, err := filepath.Abs(dir)
	if err != nil {
		return newError(ErrInternal, "resolve temp dir failed: %v", err)
	}
	root := filepath.Join(abs, "frplib")
	if err := ensureTempDir(root); err != nil {
		return err
	}

	tempDirState.Lock()
	tempDirState.dir = root
	tempDirState.Unlock()
	return nil
}

func configTempDir() (string, error) {
	tempDirState.RLock()
	dir := tempDirState.dir
	tempDirState.RUnlock()
	if dir != "" {
		return dir, nil
	}

	if cacheDir, err := os.UserCacheDir(); err == nil && cacheDir != "" {
		root := filepath.Join(cacheDir, "frplib")
		if err := ensureTempDir(root); err == nil {
			return root, nil
		}
	}
	root := filepath.Join(os.TempDir(), "frplib")
	if err := ensureTempDir(root); err == nil {
		return root, nil
	}
	return "", newError(ErrInternal, "no writable temp dir found. On Android, call SetTempDir(context.cacheDir.absolutePath) before starting frp")
}

func ensureTempDir(root string) error {
	if err := os.MkdirAll(root, 0o700); err != nil {
		return newError(ErrInternal, "create temp dir failed: %v", err)
	}

	probe, err := os.CreateTemp(root, ".probe-*.tmp")
	if err != nil {
		return newError(ErrInternal, "temp dir is not writable: %v", err)
	}
	path := probe.Name()
	if err := probe.Close(); err != nil {
		_ = os.Remove(path)
		return newError(ErrInternal, "close temp dir probe failed: %v", err)
	}
	_ = os.Remove(path)
	return nil
}
