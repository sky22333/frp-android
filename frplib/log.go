package frplib

import (
	"bytes"
	"strings"
	"sync"
	"time"

	frplog "github.com/fatedier/frp/pkg/util/log"
	goliblog "github.com/fatedier/golib/log"
)

type FrpLogCallback interface {
	OnLog(instanceID string, typ string, level string, message string)
}

var logBridge = struct {
	sync.RWMutex
	callback FrpLogCallback
}{}

func init() {
	frplog.Logger = frplog.Logger.WithOptions(goliblog.WithOutput(androidLogWriter{}))
}

func SetLogCallback(callback FrpLogCallback) {
	logBridge.Lock()
	logBridge.callback = callback
	logBridge.Unlock()
}

func emitLog(instanceID, typ, level, message string) {
	logBridge.RLock()
	callback := logBridge.callback
	logBridge.RUnlock()

	if callback == nil {
		return
	}

	message = strings.TrimSpace(message)
	if message == "" {
		return
	}

	callback.OnLog(instanceID, typ, level, message)
}

type androidLogWriter struct{}

func (androidLogWriter) Write(p []byte) (int, error) {
	emitLog("", "frp", "info", string(p))
	return len(p), nil
}

func (androidLogWriter) WriteLog(p []byte, level goliblog.Level, _ time.Time) (int, error) {
	emitLog("", "frp", level.String(), string(bytes.TrimSpace(p)))
	return len(p), nil
}
