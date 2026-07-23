package frplib

import "fmt"

const (
	ErrAlreadyRunning = "ALREADY_RUNNING"
	ErrInvalidID      = "INVALID_ID"
	ErrInvalidTempDir = "INVALID_TEMP_DIR"
	ErrInvalidToml    = "INVALID_TOML"
	ErrStartFailed    = "START_FAILED"
	ErrStopFailed     = "STOP_FAILED"
	ErrReloadFailed   = "RELOAD_FAILED"
	ErrInternal       = "INTERNAL_ERROR"
)

type coreError struct {
	code    string
	message string
}

func (e coreError) Error() string {
	if e.message == "" {
		return e.code
	}
	return fmt.Sprintf("%s: %s", e.code, e.message)
}

func newError(code, format string, args ...any) error {
	return coreError{code: code, message: fmt.Sprintf(format, args...)}
}

func errorString(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}
