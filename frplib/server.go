package frplib

import (
	"context"
	"fmt"

	"github.com/fatedier/frp/pkg/config"
	v1 "github.com/fatedier/frp/pkg/config/v1"
	"github.com/fatedier/frp/pkg/config/v1/validation"
	"github.com/fatedier/frp/pkg/policy/security"
	"github.com/fatedier/frp/server"
)

func StartServerWithID(id, configToml string) string {
	return errorString(startServerWithID(id, configToml))
}

func StopServerWithID(id string) string {
	return errorString(stopServerWithID(id))
}

func ReloadServerWithID(id, configToml string) string {
	return errorString(reloadServerWithID(id, configToml))
}

func IsServerRunningWithID(id string) bool {
	return globalManager.isRunning(instanceTypeServer, id)
}

func startServerWithID(id, configToml string) error {
	return globalManager.start(instanceTypeServer, id, configToml, newServerService)
}

func stopServerWithID(id string) error {
	return globalManager.stop(instanceTypeServer, id)
}

func reloadServerWithID(id, configToml string) error {
	if err := validateID(id); err != nil {
		return err
	}

	configPath, err := writeConfigTemp(instanceTypeServer+"-"+id+"-reload", configToml)
	if err != nil {
		return err
	}

	loaded, err := loadServer(configPath)
	if err != nil {
		removeConfigTemp(configPath)
		return newError(ErrReloadFailed, "%v", err)
	}

	if err := globalManager.restart(instanceTypeServer, id, configPath, func() (runningService, error) {
		return openServerService(loaded)
	}); err != nil {
		removeConfigTemp(configPath)
		return newError(ErrReloadFailed, "%v", err)
	}
	return nil
}

type serverService struct {
	svc *server.Service
}

func (s *serverService) Run(ctx context.Context) error {
	s.svc.Run(ctx)
	return nil
}

func (s *serverService) Close() error {
	return s.svc.Close()
}

func newServerService(configPath string) (runningService, error) {
	loaded, err := loadServer(configPath)
	if err != nil {
		return nil, err
	}
	return openServerService(loaded)
}

func openServerService(cfg *v1.ServerConfig) (runningService, error) {
	applyFrpLogger(cfg.Log.To, cfg.Log.Level, int(cfg.Log.MaxDays))

	svc, err := server.NewService(cfg)
	if err != nil {
		return nil, newError(ErrStartFailed, "create frps service failed: %v", err)
	}
	return &serverService{svc: svc}, nil
}

func loadServer(configPath string) (*v1.ServerConfig, error) {
	common, isLegacyFormat, err := config.LoadServerConfig(configPath, true)
	if err != nil {
		return nil, newError(ErrInvalidToml, "parse frps TOML failed: %v", err)
	}
	if isLegacyFormat {
		return nil, newError(ErrInvalidToml, "legacy frps config format is not supported")
	}
	if common == nil {
		return nil, newError(ErrInvalidToml, "frps config is empty")
	}

	unsafeFeatures := security.NewUnsafeFeatures(nil)
	validator := validation.NewConfigValidator(unsafeFeatures)
	warning, err := validator.ValidateServerConfig(common)
	if warning != nil {
		emitLog("", instanceTypeServer, "warn", fmt.Sprintf("%v", warning))
	}
	if err != nil {
		return nil, newError(ErrInvalidToml, "%v", err)
	}
	return common, nil
}
