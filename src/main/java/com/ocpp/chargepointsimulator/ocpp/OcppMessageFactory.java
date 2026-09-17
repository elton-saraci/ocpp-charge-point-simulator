package com.ocpp.chargepointsimulator.ocpp;

import com.ocpp.chargepointsimulator.domain.ChargePointConfig;
import com.ocpp.chargepointsimulator.domain.ConnectorState;
import com.ocpp.chargepointsimulator.utilities.EnergyMeterCalculator;
import eu.chargetime.ocpp.model.core.BootNotificationRequest;
import eu.chargetime.ocpp.model.core.ChargePointErrorCode;
import eu.chargetime.ocpp.model.core.ChargePointStatus;
import eu.chargetime.ocpp.model.core.HeartbeatRequest;
import eu.chargetime.ocpp.model.core.Location;
import eu.chargetime.ocpp.model.core.MeterValue;
import eu.chargetime.ocpp.model.core.MeterValuesRequest;
import eu.chargetime.ocpp.model.core.Reason;
import eu.chargetime.ocpp.model.core.SampledValue;
import eu.chargetime.ocpp.model.core.StartTransactionRequest;
import eu.chargetime.ocpp.model.core.StatusNotificationRequest;
import eu.chargetime.ocpp.model.core.StopTransactionRequest;
import eu.chargetime.ocpp.model.core.ValueFormat;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;

/**
 * Builds the OCPP 1.6 requests a simulated charge point sends to the central system.
 *
 * <p>Stateless on purpose, so one instance serves every charge point. The connector and charge point
 * a message belongs to are always passed in instead of being read from a shared singleton.
 */
@Component
public class OcppMessageFactory {

    // OCPP 1.6 constrains these fields; keep the values inside the limits.
    private static final String CHARGE_POINT_VENDOR = "ocpp-simulator";
    private static final String CHARGE_POINT_MODEL = "simulated-cp";
    private static final String FIRMWARE_VERSION = "1.0.0";
    private static final int SERIAL_NUMBER_MAX_LENGTH = 25;

    public BootNotificationRequest bootNotification(ChargePointConfig config) {
        BootNotificationRequest request = new BootNotificationRequest();
        request.setChargePointVendor(CHARGE_POINT_VENDOR);
        request.setChargePointModel(CHARGE_POINT_MODEL);
        request.setChargePointSerialNumber(truncate(config.chargePointId()));
        request.setFirmwareVersion(FIRMWARE_VERSION);
        request.setIccid("iccid");
        request.setImsi("TEST_IMSI");
        request.setMeterType("METER_TYPE");
        request.setMeterSerialNumber("meterModel");
        return request;
    }

    public StatusNotificationRequest statusNotification(ConnectorState connector, ChargePointStatus status) {
        StatusNotificationRequest request = new StatusNotificationRequest();
        request.setConnectorId(connector.getConnectorId());
        request.setStatus(status);
        request.setTimestamp(ZonedDateTime.now());
        request.setErrorCode(ChargePointErrorCode.NoError);
        request.setInfo("cpDoingAwesome");
        request.setVendorId("vendorId");
        request.setVendorErrorCode("noVendorErrorCode");
        return request;
    }

    public StartTransactionRequest startTransaction(ConnectorState connector, String idTag) {
        StartTransactionRequest request = new StartTransactionRequest();
        request.setConnectorId(connector.getConnectorId());
        request.setIdTag(idTag);
        request.setMeterStart(connector.getCurrentMeterValueWh());
        request.setTimestamp(ZonedDateTime.now());
        return request;
    }

    public StopTransactionRequest stopTransaction(ChargePointConfig config, ConnectorState connector, Reason reason) {
        StopTransactionRequest request = new StopTransactionRequest();
        request.setTransactionId(connector.getTransactionId());
        request.setIdTag(connector.getIdTag());
        request.setMeterStop(connector.getCurrentMeterValueWh());
        request.setTimestamp(ZonedDateTime.now());
        request.setReason(reason);
        request.setTransactionData(new MeterValue[] {
                meterValue(connector.getCurrentMeterValueWh(), config.chargingPower()) });
        return request;
    }

    /**
     * Builds a MeterValues request and advances the connector's energy register by the energy that
     * was charged during one metering interval, see {@link EnergyMeterCalculator}.
     */
    public MeterValuesRequest meterValues(ChargePointConfig config, ConnectorState connector) {
        int energyStepWh = EnergyMeterCalculator.energyStepWh(config.chargingPower(), config.meterValuesFrequency());
        int meterValueWh = connector.advanceMeterValueWh(energyStepWh);
        MeterValuesRequest request = new MeterValuesRequest();
        request.setConnectorId(connector.getConnectorId());
        request.setTransactionId(connector.getTransactionId());
        request.setMeterValue(new MeterValue[] { meterValue(meterValueWh, config.chargingPower()) });
        return request;
    }

    public HeartbeatRequest heartbeat() {
        return new HeartbeatRequest();
    }

    private MeterValue meterValue(int meterValueWh, int chargingPowerW) {
        MeterValue meterValue = new MeterValue();
        meterValue.setTimestamp(ZonedDateTime.now());
        meterValue.setSampledValue(new SampledValue[] {
                sampledMeterValue(String.valueOf(meterValueWh), "Wh", "Energy.Active.Import.Register"),
                sampledMeterValue(String.valueOf(chargingPowerW), "W", "Power.Active.Import") });
        return meterValue;
    }

    private SampledValue sampledMeterValue(String value, String unit, String measurand) {
        SampledValue sampledValue = new SampledValue();
        sampledValue.setValue(value);
        sampledValue.setUnit(unit);
        sampledValue.setMeasurand(measurand);
        sampledValue.setFormat(ValueFormat.Raw);
        sampledValue.setLocation(Location.Outlet);
        sampledValue.setContext("Sample.Periodic");
        return sampledValue;
    }

    private String truncate(String value) {
        return value.length() <= OcppMessageFactory.SERIAL_NUMBER_MAX_LENGTH ? value : value.substring(0, OcppMessageFactory.SERIAL_NUMBER_MAX_LENGTH);
    }
}
