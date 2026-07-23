package frplib

import (
	"context"

	"github.com/fatedier/frp/pkg/config"
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
	return globalManager.reload(instanceTypeServer, id, configToml, validateServerConfig, newServerService)
}

func validateServerConfig(configPath string) error {
	common, isLegacyFormat, err := config.LoadServerConfig(configPath, true)
	if err != nil {
		return newError(ErrInvalidToml, "parse frps TOML failed: %v", err)
	}
	if isLegacyFormat {
		return newError(ErrInvalidToml, "legacy frps config format is not supported")
	}
	if common == nil {
		return nil
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
	common, isLegacyFormat, err := config.LoadServerConfig(configPath, true)
	if err != nil {
		return nil, newError(ErrInvalidToml, "parse frps TOML failed: %v", err)
	}
	if isLegacyFormat {
		return nil, newError(ErrInvalidToml, "legacy frps config format is not supported")
	}

	svc, err := server.NewService(common)
	if err != nil {
		return nil, newError(ErrStartFailed, "create frps service failed: %v", err)
	}

	return &serverService{svc: svc}, nil
}
