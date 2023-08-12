package com.ocpp.chargepointsimulator.configurations;

import eu.chargetime.ocpp.feature.profile.ClientCoreEventHandler;
import eu.chargetime.ocpp.model.core.*;
import org.springframework.context.annotation.Bean;

public class ClientCoreEventConfiguration {

    @Bean
    public ClientCoreEventHandler configTestClient() {
        return new ClientCoreEventHandler() {
            @Override
            public ChangeAvailabilityConfirmation handleChangeAvailabilityRequest(ChangeAvailabilityRequest changeAvailabilityRequest) {
                return null;
            }

            @Override
            public GetConfigurationConfirmation handleGetConfigurationRequest(GetConfigurationRequest getConfigurationRequest) {
                return null;
            }

            @Override
            public ChangeConfigurationConfirmation handleChangeConfigurationRequest(ChangeConfigurationRequest changeConfigurationRequest) {
                return null;
            }

            @Override
            public ClearCacheConfirmation handleClearCacheRequest(ClearCacheRequest clearCacheRequest) {
                return null;
            }

            @Override
            public DataTransferConfirmation handleDataTransferRequest(DataTransferRequest dataTransferRequest) {
                return null;
            }

            @Override
            public RemoteStartTransactionConfirmation handleRemoteStartTransactionRequest(RemoteStartTransactionRequest remoteStartTransactionRequest) {
                return null;
            }

            @Override
            public RemoteStopTransactionConfirmation handleRemoteStopTransactionRequest(RemoteStopTransactionRequest remoteStopTransactionRequest) {
                return null;
            }

            @Override
            public ResetConfirmation handleResetRequest(ResetRequest resetRequest) {
                return null;
            }

            @Override
            public UnlockConnectorConfirmation handleUnlockConnectorRequest(UnlockConnectorRequest unlockConnectorRequest) {
                return null;
            }
        };
    }

}
