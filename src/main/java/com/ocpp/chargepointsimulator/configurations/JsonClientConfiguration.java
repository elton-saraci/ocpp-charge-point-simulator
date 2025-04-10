package com.ocpp.chargepointsimulator.configurations;

import com.ocpp.chargepointsimulator.factories.MessageRequestFactory;
import eu.chargetime.ocpp.JSONClient;
import eu.chargetime.ocpp.OccurenceConstraintException;
import eu.chargetime.ocpp.UnsupportedFeatureException;
import eu.chargetime.ocpp.feature.profile.ClientCoreEventHandler;
import eu.chargetime.ocpp.feature.profile.ClientCoreProfile;
import eu.chargetime.ocpp.feature.profile.ClientRemoteTriggerEventHandler;
import eu.chargetime.ocpp.feature.profile.ClientRemoteTriggerProfile;
import eu.chargetime.ocpp.model.core.*;
import eu.chargetime.ocpp.model.remotetrigger.TriggerMessageConfirmation;
import eu.chargetime.ocpp.model.remotetrigger.TriggerMessageStatus;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@AllArgsConstructor
@Configuration
public class JsonClientConfiguration {

    private final MessageRequestFactory messageRequestFactory;
    private final ChargePointConfiguration chargePointConfiguration;
    private static final ThreadPoolExecutor remoteExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(15);

    @Bean
    public JSONClient configureJsonClient() {
        JSONClient jsonClient = new JSONClient(new ClientCoreProfile(null));
        ClientCoreProfile clientCoreProfileConfig = new ClientCoreProfile(clientCoreEventHandler(jsonClient));
        ClientRemoteTriggerProfile clientRemoteTriggerProfile = new ClientRemoteTriggerProfile(clientRemoteTriggerEventHandlerConfiguration(jsonClient));
        jsonClient.addFeatureProfile(clientRemoteTriggerProfile);
        jsonClient.addFeatureProfile(clientCoreProfileConfig);
        return jsonClient;
    }

    public ClientRemoteTriggerEventHandler clientRemoteTriggerEventHandlerConfiguration(JSONClient jsonClient) {
        return triggerMessageRequest -> {
            try {
                log.info("Incoming TriggerMessageRequest -> {}", triggerMessageRequest);
                switch (triggerMessageRequest.getRequestedMessage()) {
                    case BootNotification:
                        jsonClient.send(messageRequestFactory.createBootNotification());
                        break;
                    case StatusNotification:
                        jsonClient.send(messageRequestFactory.createStatusNotification());
                        break;
                    case MeterValues:
                        if(chargePointConfiguration.getChargePointStatus().equals(ChargePointStatus.Charging)) {
                            jsonClient.send(messageRequestFactory.createMeterValuesRequest());
                        }
                        break;
                    case Heartbeat:
                        jsonClient.send(new HeartbeatRequest());
                        break;
                    default: log.info("The requested command not implemented yet."); break;
                }
                return new TriggerMessageConfirmation(TriggerMessageStatus.Accepted);
            } catch (Exception e) {
                log.error("Error happened: " + e.getLocalizedMessage());
                return new TriggerMessageConfirmation(TriggerMessageStatus.Rejected);
            }
        };
    }

    public ClientCoreEventHandler clientCoreEventHandler(JSONClient jsonClient) {
        return new ClientCoreEventHandler() {
            @Override
            public ChangeAvailabilityConfirmation handleChangeAvailabilityRequest(ChangeAvailabilityRequest request) {
                log.info("Incoming ChangeAvailabilityRequest -> {}", request);
                chargePointConfiguration.setChargePointStatus(request.getType().equals(AvailabilityType.Operative) ?
                        ChargePointStatus.Available : ChargePointStatus.Unavailable);
                return new ChangeAvailabilityConfirmation(AvailabilityStatus.Accepted);
            }

            @Override
            public GetConfigurationConfirmation handleGetConfigurationRequest(GetConfigurationRequest request) {
                log.info("Incoming GetConfigurationRequest -> {}", request);
                // to be implemented
                return null; // Unsupported feature
            }

            @Override
            public ChangeConfigurationConfirmation handleChangeConfigurationRequest(ChangeConfigurationRequest request) {
                log.info("Incoming ChangeConfigurationRequest -> {}", request);
                // to be implemented
                return null; // Unsupported feature
            }

            @Override
            public ClearCacheConfirmation handleClearCacheRequest(ClearCacheRequest request) {
                log.info("Incoming ClearCacheRequest -> {}", request);
                return new ClearCacheConfirmation(ClearCacheStatus.Accepted); // returning ACCEPTED, mocking a successful response
            }

            @Override
            public DataTransferConfirmation handleDataTransferRequest(DataTransferRequest request) {
                log.info("Incoming DataTransferRequest -> {}", request);
                // to be implemented
                return null; // Unsupported feature
            }

            @Override
            public RemoteStartTransactionConfirmation handleRemoteStartTransactionRequest(RemoteStartTransactionRequest request) {
                log.info("Incoming RemoteStartTransactionRequest -> {}", request);
                List<ChargePointStatus> validChargePointStatuses = List.of(ChargePointStatus.Available, ChargePointStatus.Preparing, ChargePointStatus.Reserved);
                ChargePointStatus currentChargePointStatus = chargePointConfiguration.getChargePointStatus();
                if(!validChargePointStatuses.contains(currentChargePointStatus)) {
                    log.error("RemoteStartTransactionRequest rejected, invalid ChargePointStatus: {}", currentChargePointStatus);
                    return new RemoteStartTransactionConfirmation(RemoteStartStopStatus.Rejected);
                }
                sendDelayedStartTransaction(jsonClient, request.getIdTag());
                return new RemoteStartTransactionConfirmation(RemoteStartStopStatus.Accepted);
            }

            @Override
            public RemoteStopTransactionConfirmation handleRemoteStopTransactionRequest(RemoteStopTransactionRequest request) {
                log.info("Incoming RemoteStopTransactionRequest -> {}", request);
                ChargePointStatus currentChargePointStatus = chargePointConfiguration.getChargePointStatus();
                if(!chargePointConfiguration.getChargePointStatus().equals(ChargePointStatus.Charging)) {
                    log.error("RemoteStopTransactionRequest rejected, invalid ChargePointStatus: {}", currentChargePointStatus);
                    return new RemoteStopTransactionConfirmation(RemoteStartStopStatus.Rejected);
                }
                if(!chargePointConfiguration.getTransactionId().equals(request.getTransactionId())) {
                    log.error("Wrong transaction id, rejecting the request.");
                    return new RemoteStopTransactionConfirmation(RemoteStartStopStatus.Rejected);
                }
                sendDelayedStopTransaction(jsonClient, ChargePointStatus.Finishing);
                return new RemoteStopTransactionConfirmation(RemoteStartStopStatus.Accepted);
            }

            @Override
            public ResetConfirmation handleResetRequest(ResetRequest request) {
                log.info("Incoming ResetRequest -> {}", request);
                sendDelayedStopTransaction(jsonClient, ChargePointStatus.Available);
                return new ResetConfirmation(ResetStatus.Accepted); // returning null means unsupported feature
            }

            @Override
            public UnlockConnectorConfirmation handleUnlockConnectorRequest(UnlockConnectorRequest request) {
                log.info("Incoming UnlockConnectorRequest -> {}", request);
                chargePointConfiguration.setChargePointStatus(ChargePointStatus.Available);
                sendDelayedStopTransaction(jsonClient, ChargePointStatus.Available);
                return new UnlockConnectorConfirmation(UnlockStatus.Unlocked);
            }
        };
    }

    private void sendDelayedStartTransaction(JSONClient jsonClient, String idTag) {
        chargePointConfiguration.setIdTag(idTag);
        if(chargePointConfiguration.getChargePointStatus().equals(ChargePointStatus.Preparing)) {
            Runnable jsonClientAsyncRunnableTask = () -> sendJsonClientRequests(jsonClient, idTag);
            remoteExecutor.execute(jsonClientAsyncRunnableTask);
        }
    }

    private void sendJsonClientRequests(JSONClient jsonClient, String idTag) {
        try {
            Thread.sleep(3000);
            StartTransactionRequest startTransactionRequest = messageRequestFactory.createStartTransactionRequest(idTag);
            log.info("Triggering StartTransaction -> {}", startTransactionRequest);
            StartTransactionConfirmation startTransactionConfirmation = (StartTransactionConfirmation) jsonClient.send(startTransactionRequest).toCompletableFuture().get();
            chargePointConfiguration.setTransactionId(startTransactionConfirmation.getTransactionId());
            chargePointConfiguration.setChargePointStatus(ChargePointStatus.Charging);
            chargePointConfiguration.setIdTag(idTag);
            StatusNotificationRequest statusNotificationRequest = messageRequestFactory.createStatusNotification();
            log.info("Triggering StatusNotification -> {}", startTransactionRequest);
            jsonClient.send(statusNotificationRequest);
        } catch (OccurenceConstraintException e) {
            log.error("OccurrenceConstraintException occurred while StartTransactionRequest -> {}", e.getLocalizedMessage());
        } catch (UnsupportedFeatureException e) {
            log.error("UnsupportedFeatureException occurred while sending StartTransactionRequest -> {}", e.getLocalizedMessage());
        } catch (Exception e) {
            log.error("Unknown error occured while sending -> StartTransactionRequest {}", e.getLocalizedMessage());
        }
    }

    private void sendDelayedStopTransaction(JSONClient jsonClient, ChargePointStatus chargePointStatus) {
        Runnable jsonClientAsyncRunnableTask = () -> sendJsonClientStopRequests(jsonClient, chargePointStatus);
        remoteExecutor.execute(jsonClientAsyncRunnableTask);
    }

    private void sendJsonClientStopRequests(JSONClient jsonClient, ChargePointStatus chargePointStatus) {
        try {
            Thread.sleep(3000);
            StopTransactionRequest stopTransactionRequest = messageRequestFactory.createStopTransactionRequest();
            log.info("Triggering StopTransactionRequest -> {}", stopTransactionRequest);
            jsonClient.send(stopTransactionRequest);
            chargePointConfiguration.setTransactionId(chargePointConfiguration.getTransactionId());
            chargePointConfiguration.setChargePointStatus(chargePointStatus);
            Thread.sleep(200);
            StatusNotificationRequest statusNotificationRequest = messageRequestFactory.createStatusNotification();
            log.info("Triggering StatusNotification -> {}", statusNotificationRequest);
            jsonClient.send(statusNotificationRequest);
            chargePointConfiguration.setIdTag(null);
            chargePointConfiguration.setTransactionId(0);
        } catch (OccurenceConstraintException e) {
            log.error("OccurrenceConstraintException occurred while StopTransactionRequest -> {}", e.getLocalizedMessage());
        } catch (UnsupportedFeatureException e) {
            log.error("UnsupportedFeatureException occurred while sending StopTransactionRequest -> {}", e.getLocalizedMessage());
        } catch (Exception e) {
            log.error("Unknown error occured while sending StopTransactionRequest -> {}", e.getLocalizedMessage());
        }
    }

}
