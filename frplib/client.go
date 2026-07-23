package frplib

import (
	"context"
	"fmt"
	"path/filepath"
	"time"

	"github.com/fatedier/frp/client"
	"github.com/fatedier/frp/pkg/config"
	"github.com/fatedier/frp/pkg/config/source"
	"github.com/fatedier/frp/pkg/config/v1/validation"
	"github.com/fatedier/frp/pkg/policy/featuregate"
	"github.com/fatedier/frp/pkg/policy/security"
)

func StartClientWithID(id, configToml string) string {
	return errorString(startClientWithID(id, configToml))
}

func StopClientWithID(id string) string {
	return errorString(stopClientWithID(id))
}

func ReloadClientWithID(id, configToml string) string {
	return errorString(reloadClientWithID(id, configToml))
}

func IsClientRunningWithID(id string) bool {
	return globalManager.isRunning(instanceTypeClient, id)
}

func startClientWithID(id, configToml string) error {
	return globalManager.start(instanceTypeClient, id, configToml, newClientService)
}

func stopClientWithID(id string) error {
	return globalManager.stop(instanceTypeClient, id)
}

func reloadClientWithID(id, configToml string) error {
	if err := validateID(id); err != nil {
		return err
	}

	configPath, err := writeConfigTemp(instanceTypeClient+"-"+id+"-reload", configToml)
	if err != nil {
		return err
	}

	loaded, err := loadClient(configPath)
	if err != nil {
		removeConfigTemp(configPath)
		return newError(ErrReloadFailed, "%v", err)
	}

	if err := globalManager.restart(instanceTypeClient, id, configPath, func() (runningService, error) {
		return openClientService(loaded)
	}); err != nil {
		removeConfigTemp(configPath)
		return newError(ErrReloadFailed, "%v", err)
	}
	return nil
}

type clientService struct {
	svc              *client.Service
	gracefulShutdown time.Duration
}

func (s *clientService) Run(ctx context.Context) error {
	return s.svc.Run(ctx)
}

func (s *clientService) Close() error {
	s.svc.GracefulClose(s.gracefulShutdown)
	return nil
}

type loadedClient struct {
	opts client.ServiceOptions
}

func newClientService(configPath string) (runningService, error) {
	loaded, err := loadClient(configPath)
	if err != nil {
		return nil, err
	}
	return openClientService(loaded)
}

func openClientService(loaded *loadedClient) (runningService, error) {
	applyFrpLogger(loaded.opts.Common.Log.To, loaded.opts.Common.Log.Level, int(loaded.opts.Common.Log.MaxDays))

	svc, err := client.NewService(loaded.opts)
	if err != nil {
		return nil, newError(ErrStartFailed, "create frpc service failed: %v", err)
	}

	graceful := time.Duration(0)
	if loaded.opts.Common.Transport.Protocol == "kcp" || loaded.opts.Common.Transport.Protocol == "quic" {
		graceful = 500 * time.Millisecond
	}
	return &clientService{svc: svc, gracefulShutdown: graceful}, nil
}

func loadClient(configPath string) (*loadedClient, error) {
	result, err := config.LoadClientConfigResult(configPath, true)
	if err != nil {
		return nil, newError(ErrInvalidToml, "parse frpc TOML failed: %v", err)
	}
	if result.IsLegacyFormat {
		return nil, newError(ErrInvalidToml, "legacy frpc config format is not supported")
	}
	if result.Common == nil {
		return nil, newError(ErrInvalidToml, "frpc common config is empty")
	}

	if len(result.Common.FeatureGates) > 0 {
		if err := featuregate.SetFromMap(result.Common.FeatureGates); err != nil {
			return nil, newError(ErrInvalidToml, "featureGates: %v", err)
		}
	}

	unsafeFeatures := security.NewUnsafeFeatures(nil)

	configSource := source.NewConfigSource()
	if err := configSource.ReplaceAll(result.Proxies, result.Visitors); err != nil {
		return nil, newError(ErrStartFailed, "load frpc config source failed: %v", err)
	}

	var storeSource *source.StoreSource
	if result.Common.Store.IsEnabled() {
		storePath := result.Common.Store.Path
		if storePath != "" && !filepath.IsAbs(storePath) {
			storePath = filepath.Join(filepath.Dir(configPath), storePath)
		}
		s, err := source.NewStoreSource(source.StoreSourceConfig{Path: storePath})
		if err != nil {
			return nil, newError(ErrStartFailed, "create store source failed: %v", err)
		}
		storeSource = s
	}

	aggregator := source.NewAggregator(configSource)
	if storeSource != nil {
		aggregator.SetStoreSource(storeSource)
	}

	proxyCfgs, visitorCfgs, err := aggregator.Load()
	if err != nil {
		return nil, newError(ErrInvalidToml, "load config from sources failed: %v", err)
	}
	proxyCfgs, visitorCfgs = config.FilterClientConfigurers(result.Common, proxyCfgs, visitorCfgs)
	proxyCfgs = config.CompleteProxyConfigurers(proxyCfgs)
	visitorCfgs = config.CompleteVisitorConfigurers(visitorCfgs)

	warning, err := validation.ValidateAllClientConfig(result.Common, proxyCfgs, visitorCfgs, unsafeFeatures)
	if warning != nil {
		emitLog("", instanceTypeClient, "warn", fmt.Sprintf("%v", warning))
	}
	if err != nil {
		return nil, newError(ErrInvalidToml, "%v", err)
	}

	return &loadedClient{opts: client.ServiceOptions{
		Common:                 result.Common,
		ConfigFilePath:         configPath,
		ConfigSourceAggregator: aggregator,
		UnsafeFeatures:         unsafeFeatures,
	}}, nil
}
