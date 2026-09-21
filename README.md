# ocpp-charge-point-simulator

An OCPP 1.6 charge point simulator built on the [Java-OCA-OCPP](https://github.com/ChargeTimeEU/Java-OCA-OCPP) library.

One instance runs **any number of charge points**. Each has its own WebSocket connection to the central system, its own
connectors and, optionally, its own HTTP Basic credentials. Charge points are created, inspected and driven over the
REST API while the application runs, so you no longer need a container per charge point.

A small web console is bundled with the simulator and served from the same port, so the whole thing is one process and
one image.

## Web console

Open `http://localhost:8080/` after starting the simulator.

- **Fleet** — every charge point as a card: connection state, power, metering interval, per-connector status, last
  error, and one-click connect/disconnect/remove. Adding or editing a charge point opens a dialog with validation and a
  live preview of the resulting session URL and the meter value step.
- **Control** — one station in detail: its definition, a connector panel per connector with a power gauge, energy
  register, live session timer and an energy trend, buttons to plug in, tap a card and plug out, plus an activity feed
  that turns the polled state into a readable story (status transitions, transaction start/stop, errors).
- **Deep links** — the control room of a charge point has an address of its own, `/station/<charge-point-id>` (for
  example `/station/CP_SIM_001`), so it can be bookmarked, shared and reloaded without losing the station. The back and
  forward buttons move between the fleet and the control rooms you visited; a link to a charge point the simulator does
  not have falls back to the fleet with a note in the activity log.
- **Activity logs** — the control room lists the selected station's events plus the console-wide ones (a failing poll,
  the console starting up), and the fleet view lists every station with the station named on each row. In the fleet the
  log stays out of the way behind the **Activity** button in the header, off by default, whose counter shows how many
  entries are waiting; the control room always shows its own. **Refresh** pulls the latest state right away instead of
  waiting for the next poll tick, **Export** downloads the list as CSV (`timestamp, level, chargePoint, message`, oldest
  first) so a session can be attached to a bug report or opened in a spreadsheet, and **Clear** forgets the history at
  the same scope as the list it sits in.

It is hand-written HTML/CSS/JavaScript served from `src/main/resources/static`, so there is no Node build step and no
second container. It refreshes from the REST API every two seconds, and editing a station rebuilds it in place: running
transactions are closed properly, energy registers of connectors that still exist are kept, and the connection state is
preserved.

The console carries the Chargomate identity — ink and emerald palette, Space Grotesk over Inter, the Chargomate mark —
and credits the author in its footer.

## How to run it locally

Project specs: Java 25, Spring Boot 3.5.16.

```bash
./mvnw spring-boot:run
```

Without `CHARGE_POINT_ID` the simulator starts with an empty registry and you create charge points over HTTP. Set it to
keep the single charge point behaviour, where one charge point is registered and connected on startup.

## Configuring charge points

A charge point definition is a small JSON document: id, central system URL, optional credentials, charging power in
Watt, metering frequency in seconds and the connector ids it exposes, for example:

```bash
curl -X POST localhost:8080/api/charge-points -H 'Content-Type: application/json' -d '{
  "chargePointId": "CP_1",
  "centralSystemUrl": "ws://localhost:8080",
  "chargingPower": 11000,
  "meterValuesFrequency": 30,
  "connectorIds": [1, 2]
}'
```

The API covers the whole lifecycle (register, list, inspect, update, connect, disconnect, remove) and the connector
operations (plug-in, plug-out, RFID authorization). Every endpoint, its parameters and the available body fields are
documented in the Swagger UI at `http://localhost:8080/swagger-ui/index.html`.

### Defaults

Everything has a default in `application.yml` under `simulator.defaults`, used for the startup charge point and for the
fields a request leaves out:

| Env var | Default | Notes |
| --- | --- | --- |
| `CHARGE_POINT_ID` | *(empty)* | Empty means: start with no charge point. |
| `CENTRAL_SYSTEM_URL` | `ws://localhost:8080` | |
| `CONNECTOR_IDS` | `1` | Comma separated, e.g. `1,2`. `CONNECTOR_ID` still works. |
| `CHARGING_POWER` | `5000` | Watt. |
| `METER_VALUES_FREQUENCY` | `60` | Seconds. |
| `OCPP_USERNAME` / `OCPP_PASSWORD` | *(empty)* | Optional HTTP Basic auth. |
| `PORT` | `8080` | HTTP port of the simulator itself. |

`METER_VALUES_STEP` no longer exists: the meter value step is calculated, not configured.

## Behaviour worth knowing

- **Meter values follow physics.** Energy is power over time, $E[\text{Wh}] = P[\text{W}] \cdot t[\text{s}] / 3600$. At
  11 kW with a 30 s interval the register grows by 92 Wh per `MeterValues` message. Each charge point reports on its
  own frequency; heartbeats are sent every 15 seconds.
- **Connectors are independent.** Status, id tag, transaction id and meter register are tracked per connector, so one
  connector can charge while another is available.
- **Authentication** uses the library's own support: `username` + `password` become the HTTP Basic credentials of the
  OCPP handshake. Credentials are never returned by the API, only an `authenticated` flag.
- **Failures are not swallowed.** API errors come back as Problem Details (`400` invalid definition, `404` unknown
  charge point or connector, `409` duplicate or not connected, `502` central system unreachable or rejecting).
  Failures with no caller, such as incoming OCPP requests, scheduled metering or a failed connection attempt, are
  logged with their stack trace and exposed as `lastError` on the charge point.
- **Connections are not retried silently.** A dropped connection is reported as disconnected, and reconnecting is an
  explicit call.

## Dockerizing the app

The `Dockerfile` is a multi-stage build, so the jar is built for you:

```bash
docker build -t ocpp-simulator .
docker run -p 8080:8080 -e CENTRAL_SYSTEM_URL=ws://host.docker.internal:8080 -e CHARGE_POINT_ID=CP_SIM_001 ocpp-simulator
```

The connection to the central system is outbound, so no inbound firewall rules are needed.

## Tests

```bash
./mvnw test
```

Covers the metering formula, charge point validation, the registry, the station update path, the REST API including its
status mapping, and real WebSocket connections (several charge points in parallel, the `ocpp1.6` subprotocol,
BootNotification and Basic auth).

## Next steps
- Add the logic for SUSPENDED connector status
- Send an authorization request after a remote start approval
- Optional auto-reconnect for dropped connections
- Implement smart charging, change configuration and change availability
- Persist charge point definitions so they survive a restart
- Add signed values

## References
- [OCPP OFFICIAL DOCUMENTATION](https://www.oasis-open.org/committees/download.php/58944/ocpp-1.6.pdf)
- [OCPP client-server library](https://github.com/ChargeTimeEU/Java-OCA-OCPP)

## License

[MIT](LICENSE) — do whatever you want with it: use it, change it, ship it, sell it, just keep the copyright notice and
don't hold the author liable.

The OCPP library it builds on ([Java-OCA-OCPP](https://github.com/ChargeTimeEU/Java-OCA-OCPP)) is MIT as well, while
Spring Boot, springdoc and Gson are Apache-2.0 and Lombok and Java-WebSocket are MIT, so nothing in the dependency tree
conflicts with it.
