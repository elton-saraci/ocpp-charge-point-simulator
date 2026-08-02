# ocpp-charge-point-simulator

## Introduction:
A charge point simulator based on the OCPP protocol.<br />
This simulator has been built using an [OCPP client-server Springboot library](https://github.com/ChargeTimeEU/Java-OCA-OCPP).
Once you run the application it connects to the server URL that is defined on the application.yml file (or via the `CENTRAL_SYSTEM_URL` / `CHARGE_POINT_ID` environment variables). This connection takes place in the StartupConfiguration.java file, where we also do the initialization of our fake charge point settings. Once connected, the simulator sends a `BootNotification` to register the charge point.

## How to run it locally
The project specs: Java 25, Springboot version 3.5.16 <br /> 
You only need to specify the configurations on the application.yml file (every value can also be overridden via environment variables, all have defaults in `application.yml`):
- central-system-url: the url of the OCPP server (CSMS). The simulator connects to `<central-system-url>/<charge-point-id>` (env: `CENTRAL_SYSTEM_URL`)
- charge-point-id (env: `CHARGE_POINT_ID`)
- connector-id: for now it only supports one single connector (env: `CONNECTOR_ID`)
- charging-power: currently not being used. Smart charging has not been implemented yet, so the power value will remain static the whole time (env: `CHARGING_POWER`)
- meter-values.step: the amount of energy in Wh we want to send for every meter value (env: `METER_VALUES_STEP`)
- meter-values.frequency: it's supposed to be the initial meter value frequency; currently not being used (env: `METER_VALUES_FREQUENCY`)

## Dockerizing the app
The included `Dockerfile` is a multi-stage build: it compiles the project with Maven and produces a slim JRE runtime image, so you do **not** need to build the jar beforehand.

Run the following commands:
- docker build -t ocpp-simulator .
- docker run -p 8080:8080 -e CHARGE_POINT_ID=CP_SIM_001 ocpp-simulator

The simulator will connect to `<CENTRAL_SYSTEM_URL>/<CHARGE_POINT_ID>` and keep the connection alive by sending OCPP Heartbeats every 15 seconds.

## Deploying with Docker
The Dockerfile can be deployed to any container platform (Render, Heroku, Fly.io, AWS, Azure, ...).

1. Build the image: `docker build -t ocpp-simulator .`
2. Run it with the required environment variables (all have defaults in `application.yml`):
   - `CENTRAL_SYSTEM_URL` — the OCPP central system (CSMS) URL
   - `CHARGE_POINT_ID` — use a unique id per charge point instance
   - `CONNECTOR_ID` — default `1`
   - `CHARGING_POWER` — default `5`
   - `METER_VALUES_STEP` — default `200`
   - `METER_VALUES_FREQUENCY` — default `60`
3. Expose the app's HTTP port: the app binds to a `PORT` environment variable when set, otherwise it defaults to `8080`.

Notes:
- The connection to the central system is **outbound** (the simulator is a WebSocket client), so no inbound firewall rules are needed.
- The app also runs its own HTTP server exposing the Swagger UI at `/swagger-ui/index.html` and the charge point control endpoints under `/api/charge-point/...` (plug-in, rfid, plug-out).
- To deploy multiple charge points, deploy one instance per charge point and give each a unique `CHARGE_POINT_ID`.

## API documentation
This project contains the Open API dependency and by default it runs locally on port 8080. <br /> 

The API details can be found on the swagger ui: http://localhost:8080/swagger-ui/index.html#/

## Next steps
- Add the logic for SUSPENDED connector status
- Send authorization request after the remote start approval
- Refactor the long files and get rid of code duplication
- Add error handling (right now all the exceptions are being swallowed)
- Write tests
- Implement the smart charging feature, change configuration and change availability
- The meter value step MUST be calculated as time(hrs) * power(W)
- Think of adding a database rather than storing the configurations during application runtime
- Add signed values

## References
- [OCPP OFFICIAL DOCUMENTATION](https://www.oasis-open.org/committees/download.php/58944/ocpp-1.6.pdf)
- [OCPP client-server library](https://github.com/ChargeTimeEU/Java-OCA-OCPP)

## References
- [OCPP OFFICIAL DOCUMENTATION](https://www.oasis-open.org/committees/download.php/58944/ocpp-1.6.pdf)
- [OCPP client-server library](https://github.com/ChargeTimeEU/Java-OCA-OCPP)
