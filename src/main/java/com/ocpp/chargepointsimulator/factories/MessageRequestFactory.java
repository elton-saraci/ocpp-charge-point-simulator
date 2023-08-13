package com.ocpp.chargepointsimulator.factories;

import com.ocpp.chargepointsimulator.configurations.ChargePointConfiguration;
import eu.chargetime.ocpp.model.core.*;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.time.ZonedDateTime;

@Configuration
@Slf4j
@AllArgsConstructor
@SuppressWarnings("deprecation")
public class MessageRequestFactory {

    private final ChargePointConfiguration chargePointConfiguration;

    public BootNotificationRequest createBootNotification() {
        BootNotificationRequest bootNotificationRequest = new BootNotificationRequest();
        bootNotificationRequest.setChargePointModel("TEST_POINT");
        bootNotificationRequest.setImsi("TEST_IMSI");
        bootNotificationRequest.setMeterType("METER_TYPE");
        bootNotificationRequest.setChargePointModel("cpModel");
        bootNotificationRequest.setMeterSerialNumber("meterModel");
        bootNotificationRequest.setFirmwareVersion("frmVersion");
        bootNotificationRequest.setChargePointSerialNumber("cpSerialNr");
        bootNotificationRequest.setIccid("iccid");
        bootNotificationRequest.setChargePointVendor("vendor");
        return bootNotificationRequest;
    }

    public StatusNotificationRequest createStatusNotification() {
        StatusNotificationRequest statusNotificationRequest = new StatusNotificationRequest();
        statusNotificationRequest.setStatus(chargePointConfiguration.getChargePointStatus());
        statusNotificationRequest.setConnectorId(chargePointConfiguration.getConnectorId());
        statusNotificationRequest.setTimestamp(ZonedDateTime.now());
        statusNotificationRequest.setInfo("cpDoingAwesome");
        statusNotificationRequest.setErrorCode(ChargePointErrorCode.NoError);
        statusNotificationRequest.setVendorId("vendorId");
        statusNotificationRequest.setVendorErrorCode("noVendorErrorCode");
        return statusNotificationRequest;
    }

    public StartTransactionRequest createStartTransactionRequest(String idTag) {
        StartTransactionRequest startTransactionRequest = new StartTransactionRequest();
        startTransactionRequest.setMeterStart(chargePointConfiguration.getCurrentMeterValue());
        startTransactionRequest.setIdTag(idTag);
        startTransactionRequest.setTimestamp(ZonedDateTime.now());
        startTransactionRequest.setConnectorId(chargePointConfiguration.getConnectorId());
        return startTransactionRequest;
    }

    public StopTransactionRequest createStopTransactionRequest() {
        StopTransactionRequest stopTransactionRequest = new StopTransactionRequest();
        stopTransactionRequest.setTransactionId(chargePointConfiguration.getTransactionId());
        stopTransactionRequest.setIdTag(chargePointConfiguration.getIdTag());
        stopTransactionRequest.setMeterStop(chargePointConfiguration.getCurrentMeterValue());
        stopTransactionRequest.setTimestamp(ZonedDateTime.now());
        stopTransactionRequest.setTransactionData(new MeterValue[]{meterValue()});
        stopTransactionRequest.setReason(Reason.Remote);
        return stopTransactionRequest;
    }

    public MeterValuesRequest createMeterValuesRequest() {
        MeterValuesRequest meterValuesRequest = new MeterValuesRequest();
        meterValuesRequest.setConnectorId(chargePointConfiguration.getConnectorId());
        meterValuesRequest.setTransactionId(chargePointConfiguration.getTransactionId());
        meterValuesRequest.setMeterValue(new MeterValue[]{meterValue()});
        return meterValuesRequest;
    }

    public MeterValue meterValue() {
        MeterValue meterValue = new MeterValue();
        meterValue.setTimestamp(ZonedDateTime.now());
        meterValue.setSampledValue(new SampledValue[] { sampledEnergyValue(), sampledPowerValue() });
        return meterValue;
    }

    private SampledValue sampledEnergyValue() {
        SampledValue sampledValue = new SampledValue();
        chargePointConfiguration.setCurrentMeterValue(chargePointConfiguration.getCurrentMeterValue() + chargePointConfiguration.getMeterValuesStep());
        sampledValue.setPhase(null);
        sampledValue.setValue(String.valueOf(chargePointConfiguration.getCurrentMeterValue()));
        sampledValue.setUnit("Wh");
        sampledValue.setMeasurand("Energy.Active.Import.Register");
        sampledValue.setFormat(ValueFormat.Raw);
        sampledValue.setLocation(Location.Outlet);
        sampledValue.setContext("Sample.Periodic");
        return sampledValue;
    }

    private SampledValue sampledPowerValue() {
        SampledValue sampledValue = new SampledValue();
        chargePointConfiguration.setCurrentMeterValue(chargePointConfiguration.getCurrentMeterValue() + chargePointConfiguration.getMeterValuesStep());
        sampledValue.setPhase(null);
        sampledValue.setValue(String.valueOf(chargePointConfiguration.getChargingPower()));
        sampledValue.setUnit("W");
        sampledValue.setMeasurand("Power.Active.Import");
        sampledValue.setFormat(ValueFormat.Raw);
        sampledValue.setLocation(Location.Outlet);
        sampledValue.setContext("Sample.Periodic");
        return sampledValue;
    }

}
