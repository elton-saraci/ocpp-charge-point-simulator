/* =============================================================================
   OCPP Fleet Console — front end for the charge point simulator.
   Vanilla ES2020, no build step. Polls the simulator REST API and patches the
   DOM in place, so polling never restarts entrance animations or drops hover.
   ========================================================================== */
(() => {
    'use strict';

    const API = '/api/charge-points';
    const POLL_MS = 2000;
    const GAUGE_RADIUS = 52;
    const GAUGE_CIRCUMFERENCE = 2 * Math.PI * GAUGE_RADIUS;
    const POWER_SCALE_W = 22000;   // visual scale of the power gauge (AC ceiling)
    const HISTORY_POINTS = 40;
    const MAX_LOG_ROWS = 200;
    const ALL_LOG_EMPTY = 'Actions and observed state changes of every station show up here.';
    const STATION_LOG_EMPTY = 'Local actions and observed state changes of this station show up here.';
    const CONFIRM_TIMEOUT_MS = 5000;

    const state = {
        stations: [],
        view: 'fleet',
        filter: '',
        selectedId: localStorage.getItem('ocpp.selected') ?? null,
        tags: JSON.parse(localStorage.getItem('ocpp.tags') ?? '["tag1"]'),
        previous: new Map(),   // "cpId#connection" | "cpId#error" | "cpId/connectorId" -> last value
        history: new Map(),    // "cpId/connectorId" -> [Wh]
        busy: new Set(),
        confirming: new Set(),
        log: [],
        logScope: undefined,   // scope the log lists were built for; forces one render at startup
        showFleetLog: localStorage.getItem('ocpp.activity') === 'on',
        apiDown: false
    };

    const $ = (selector) => document.querySelector(selector);
    const dom = {
        metrics: $('#metrics'),
        banner: $('#banner'),
        bannerDetail: $('#banner-detail'),
        fleet: $('#view-fleet'),
        station: $('#view-station'),
        grid: $('#grid'),
        fleetSub: $('#fleet-sub'),
        fleetEmpty: $('#fleet-empty'),
        emptyTitle: $('#empty-title'),
        emptyBody: $('#empty-body'),
        filter: $('#filter'),
        picker: $('#station-picker'),
        stationTitle: $('#station-title'),
        stationSub: $('#station-sub'),
        stationState: $('#station-state'),
        stationKv: $('#station-kv'),
        connectors: $('#connectors'),
        rfidTag: $('#rfid-tag'),
        log: $('#log'),
        fleetLog: $('#fleet-log'),
        fleetLogPanel: $('#fleet-log-panel'),
        fleetLogCount: $('#fleet-log-count'),
        toggleFleetLog: $('#toggle-fleet-log'),
        refreshFleet: $('#refresh-fleet'),
        refreshStation: $('#refresh-station'),
        refreshStationLog: $('#refresh-station-log'),
        refreshFleetLog: $('#refresh-fleet-log'),
        exportStationLog: $('#export-station-log'),
        exportFleetLog: $('#export-fleet-log'),
        clearStationLog: $('#clear-station-log'),
        clearFleetLog: $('#clear-fleet-log'),
        tabFleet: $('#tab-fleet'),
        tabStation: $('#tab-station'),
        stationEdit: $('#station-edit'),
        stationToggle: $('#station-toggle'),
        stationRemove: $('#station-remove'),
        dialog: $('#station-dialog'),
        form: $('#station-form'),
        formTitle: $('#dialog-title'),
        formError: $('#form-error'),
        formSubmit: $('#form-submit'),
        preview: $('#form-preview'),
        toasts: $('#toasts')
    };

    /* ------------------------------------------------------------------ utils */

    const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g,
        (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));

    const connectorKey = (chargePointId, connectorId) => `${chargePointId}/${connectorId}`;

    const formatPower = (watt) => `${(watt / 1000).toFixed(1)} kW`;
    const formatKwh = (wh) => `${(wh / 1000).toFixed(wh < 10000 ? 2 : 1)} kWh`;
    const formatWh = (wh) => `${wh.toLocaleString('en-US')} Wh`;
    const formatClock = (date) => date.toTimeString().slice(0, 8);

    const formatDuration = (ms) => {
        const total = Math.max(0, Math.floor(ms / 1000));
        const pad = (n) => String(n).padStart(2, '0');
        const hours = Math.floor(total / 3600);
        return hours > 0
            ? `${pad(hours)}:${pad(Math.floor((total % 3600) / 60))}:${pad(total % 60)}`
            : `${pad(Math.floor(total / 60))}:${pad(total % 60)}`;
    };

    const totalWh = (station) => station.connectors.reduce((sum, connector) => sum + connector.meterValueWh, 0);
    const chargingCount = (station) => station.connectors.filter((connector) => connector.status === 'Charging').length;
    const field = (name) => dom.form.elements.namedItem(name);

    /** Elapsed time of a running transaction, formatted for display. */
    const sessionTime = (startedAtIso) => {
        const startedAt = Date.parse(startedAtIso);
        return Number.isNaN(startedAt) ? '—' : formatDuration(Date.now() - startedAt);
    };

    /* -------------------------------------------------------------- activity */

    function logRow(entry, withStation) {
        const row = document.createElement('li');
        row.className = 'log__row';
        row.dataset.level = entry.level;
        row.innerHTML = `<span class="log__time">${formatClock(entry.at)}</span>`
            + (withStation && entry.chargePointId
                ? `<span class="log__cp" title="${escapeHtml(entry.chargePointId)}">`
                    + `${escapeHtml(entry.chargePointId)}</span>`
                : '')
            + `<span class="log__msg">${escapeHtml(entry.message)}</span>`;
        return row;
    }

    /** What the control-room log is scoped to; null in the fleet view, whose log shows everything. */
    const logScope = () => (state.view === 'station' ? state.selectedId : null);

    /**
     * Entries of one charge point, plus the console-wide ones (polling failures, readiness), which
     * are not about any single station and therefore belong in every log.
     */
    const logEntriesFor = (chargePointId) => (chargePointId
        ? state.log.filter((entry) => !entry.chargePointId || entry.chargePointId === chargePointId)
        : state.log);

    function addLog(level, message, chargePointId = null) {
        const entry = { at: new Date(), level, message, chargePointId };
        state.log.unshift(entry);
        if (state.log.length > MAX_LOG_ROWS) {
            state.log.length = MAX_LOG_ROWS;
        }
        // prepend only the new row: re-rendering a list would replay its entrance animations
        liveLog(dom.fleetLog, entry, true);
        if (!chargePointId || chargePointId === logScope()) {
            liveLog(dom.log, entry, false);
        }
    }

    /** Adds one row on top of a list, keeping its placeholder and its cap in sync. */
    function liveLog(list, entry, withStation) {
        list.querySelector('.log__empty')?.remove();
        list.prepend(logRow(entry, withStation));
        while (list.children.length > MAX_LOG_ROWS) {
            list.lastElementChild.remove();
        }
    }

    function renderLogInto(list, entries, emptyText, withStation) {
        list.innerHTML = '';
        if (!entries.length) {
            list.innerHTML = `<li class="log__empty">${escapeHtml(emptyText)}</li>`;
            return;
        }
        entries.forEach((entry) => list.append(logRow(entry, withStation)));
    }

    function renderLog() {
        renderLogInto(dom.fleetLog, state.log, ALL_LOG_EMPTY, true);
        renderLogInto(dom.log, logEntriesFor(state.selectedId), STATION_LOG_EMPTY, false);
        state.logScope = logScope();
    }

    /** Rebuilds the lists only when the scope changed, so live rows never restart their animation. */
    function syncLog() {
        if (state.logScope !== logScope()) {
            renderLog();
        }
    }

    /**
     * The fleet keeps its log out of the way until it is asked for: the panel is hidden by default and
     * the button in the header shows how much is waiting in it. The control room always shows its own.
     */
    function renderFleetLogToggle() {
        const open = state.showFleetLog;
        dom.fleetLogPanel.hidden = !open;
        dom.toggleFleetLog.setAttribute('aria-expanded', String(open));
        dom.toggleFleetLog.classList.toggle('btn--accent', open);
        dom.fleetLogCount.textContent = state.log.length;
    }

    /** @param {boolean} open whether the fleet log is being shown, remembered across reloads */
    function setFleetLogVisible(open) {
        state.showFleetLog = open;
        localStorage.setItem('ocpp.activity', open ? 'on' : 'off');
        renderFleetLogToggle();
        if (open) {
            dom.fleetLogPanel.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        }
    }

    /** RFC 4180: a field is quoted when it holds a comma, a quote or a line break. */
    const csvCell = (value) => {
        const text = String(value ?? '');
        return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
    };

    /** A file name that survives Windows and the shell: no colons, nothing exotic. */
    const exportName = (chargePointId) => {
        const scope = (chargePointId ?? 'all-stations').replace(/[^A-Za-z0-9._-]+/g, '_');
        const stamp = new Date().toISOString().slice(0, 19).replace(/:/g, '-');
        return `activity-${scope}-${stamp}.csv`;
    };

    /** Hands text to the browser as a download; no server round trip and nothing to clean up. */
    function download(text, name) {
        const url = URL.createObjectURL(new Blob([text], { type: 'text/csv;charset=utf-8' }));
        const link = document.createElement('a');
        link.href = url;
        link.download = name;
        document.body.append(link);
        link.click();
        link.remove();
        URL.revokeObjectURL(url);
    }

    /**
     * Downloads what a log shows, oldest entry first so the file reads chronologically. The panels keep
     * the newest MAX_LOG_ROWS entries and show them on top, so the file covers exactly that window.
     */
    function exportLog(chargePointId) {
        const entries = logEntriesFor(chargePointId);
        if (!entries.length) {
            toast('error', 'Nothing to export', 'The activity log is empty.');
            return;
        }
        const rows = [['timestamp', 'level', 'chargePoint', 'message'], ...[...entries].reverse().map((entry) => [
            entry.at.toISOString(), entry.level, entry.chargePointId ?? '', entry.message
        ])];
        const name = exportName(chargePointId);
        download(rows.map((row) => row.map(csvCell).join(',')).join('\r\n'), name);
        addLog('info', `Exported ${entries.length} log ${entries.length === 1 ? 'entry' : 'entries'} to ${name}`,
            chargePointId);
        toast('ok', 'Log exported', name);
    }

    function toast(level, title, detail) {
        const node = document.createElement('div');
        node.className = 'toast';
        node.dataset.level = level;
        node.innerHTML = `<div><strong>${escapeHtml(title)}</strong>`
            + `${detail ? `<p>${escapeHtml(detail)}</p>` : ''}</div>`;
        dom.toasts.append(node);
        setTimeout(() => node.remove(), level === 'error' ? 7000 : 4000);
        while (dom.toasts.children.length > 3) {
            dom.toasts.firstElementChild.remove();
        }
    }

    /* ------------------------------------------------------------------- api */

    async function api(method, path, body) {
        const options = { method, headers: {} };
        if (body !== undefined) {
            options.headers['Content-Type'] = 'application/json';
            options.body = JSON.stringify(body);
        }
        const response = await fetch(new URL(path, document.baseURI), options);
        const text = await response.text();
        const payload = text ? JSON.parse(text) : null;
        if (!response.ok) {
            throw new Error(payload?.detail || payload?.title || `${response.status} ${response.statusText}`);
        }
        return payload;
    }

    function withParams(path, params) {
        const url = new URL(path, document.baseURI);
        Object.entries(params).forEach(([name, value]) => {
            if (value !== undefined && value !== null && value !== '') {
                url.searchParams.set(name, value);
            }
        });
        return url.pathname + url.search;
    }

    /* --------------------------------------------------------------- actions */

    /** Runs a REST action and logs its outcome against the charge point it belongs to. */
    async function run(actionKey, action, { successMessage = null, chargePointId = null } = {}) {
        if (state.busy.has(actionKey)) {
            return undefined;
        }
        state.busy.add(actionKey);
        render();
        try {
            const result = await action();
            if (successMessage) {
                addLog('ok', successMessage, chargePointId);
                toast('ok', successMessage);
            }
            await refresh();
            return result;
        } catch (error) {
            addLog('error', `${actionKey}: ${error.message}`, chargePointId);
            toast('error', 'Request failed', error.message);
            return undefined;
        } finally {
            state.busy.delete(actionKey);
            render();
        }
    }

    const createStation = (payload) => run('create', () => api('POST', API, payload), {
        successMessage: `Charge point ${payload.chargePointId} registered`,
        chargePointId: payload.chargePointId
    });

    const updateStation = (chargePointId, payload) => run(`update:${chargePointId}`,
        () => api('PUT', withParams(API, { cpId: chargePointId }), payload), {
            successMessage: `Charge point ${chargePointId} updated`,
            chargePointId
        });

    const removeStation = (chargePointId) => run(`remove:${chargePointId}`,
        () => api('DELETE', withParams(API, { cpId: chargePointId })), {
            successMessage: `Charge point ${chargePointId} removed`,
            chargePointId
        });

    const connectStation = (chargePointId) => run(`connect:${chargePointId}`, async () => {
        const session = await api('POST', withParams(`${API}/connect`, { cpId: chargePointId }));
        addLog('live', `${chargePointId} connecting to ${session.webSocketUrl}`, chargePointId);
        return session;
    }, { chargePointId });

    const disconnectStation = (chargePointId) => run(`disconnect:${chargePointId}`,
        () => api('POST', withParams(`${API}/disconnect`, { cpId: chargePointId })), {
            successMessage: `${chargePointId} disconnected`,
            chargePointId
        });

    function connectorAction(station, connectorId, action, idTag) {
        const actionKey = `${action}:${connectorKey(station.chargePointId, connectorId)}`;
        return run(actionKey, async () => {
            if (action === 'rfid') {
                const result = await api('POST', withParams(`${API}/connectors/rfid`,
                    { cpId: station.chargePointId, connectorId, idTag }));
                if (!result.accepted) {
                    throw new Error(`card ${idTag} refused by the central system (${result.centralSystemStatus})`);
                }
                rememberTag(idTag);
                addLog('ok', `${station.chargePointId} #${connectorId} authorized card ${idTag}`,
                    station.chargePointId);
                return result;
            }
            addLog(action === 'plug-in' ? 'live' : 'warn',
                `${station.chargePointId} #${connectorId} cable ${action === 'plug-in' ? 'plugged in' : 'unplugged'}`,
                station.chargePointId);
            return api('POST', withParams(`${API}/connectors/${action}`, { cpId: station.chargePointId, connectorId }));
        }, { chargePointId: station.chargePointId });
    }

    function rememberTag(idTag) {
        state.tags = [idTag, ...state.tags.filter((tag) => tag !== idTag)].slice(0, 5);
        localStorage.setItem('ocpp.tags', JSON.stringify(state.tags));
    }

    /* ------------------------------------------------------ polling & diffing */

    async function refresh() {
        try {
            const stations = await api('GET', API);
            detectChanges(stations);
            state.stations = stations;
            if (state.apiDown) {
                state.apiDown = false;
                addLog('ok', 'simulator reachable again');
            }
        } catch (error) {
            if (!state.apiDown) {
                state.apiDown = true;
                dom.bannerDetail.textContent = error.message;
                addLog('error', `polling failed: ${error.message}`);
            }
        }
        render();
    }

    /**
     * Pulls the latest state right now instead of waiting for the next tick. The log only grows when a
     * poll observes a change, and polling pauses while the tab is in the background, so a manual pull
     * is the way to see what the charge points did in the meantime.
     */
    async function pullLatest(button) {
        button.disabled = true;
        button.classList.add('is-busy');
        try {
            await refresh();
            renderLog();
        } finally {
            button.classList.remove('is-busy');
            button.disabled = false;
        }
    }

    /** Turns plain polling into readable history: transitions, transactions, errors. */
    function detectChanges(stations) {
        const seen = new Set();
        for (const station of stations) {
            const connectionKey = `${station.chargePointId}#connection`;
            const errorKey = `${station.chargePointId}#error`;
            seen.add(connectionKey);
            seen.add(errorKey);

            const wasConnected = state.previous.get(connectionKey);
            if (wasConnected !== undefined && wasConnected !== station.connected) {
                addLog(station.connected ? 'ok' : 'warn',
                    `${station.chargePointId} ${station.connected ? 'session opened' : 'session closed'}`,
                    station.chargePointId);
            }
            state.previous.set(connectionKey, station.connected);

            if (station.lastError && state.previous.get(errorKey) !== station.lastError) {
                addLog('error', `${station.chargePointId}: ${station.lastError}`, station.chargePointId);
            }
            state.previous.set(errorKey, station.lastError ?? null);

            for (const connector of station.connectors) {
                const id = connectorKey(station.chargePointId, connector.connectorId);
                seen.add(id);
                const before = state.previous.get(id);

                if (before && before.status !== connector.status) {
                    addLog(connector.status === 'Charging' ? 'live' : 'ok',
                        `${station.chargePointId} #${connector.connectorId} ${before.status} → ${connector.status}`,
                        station.chargePointId);
                }
                if (!before || before.transactionId !== connector.transactionId) {
                    if (connector.transactionId) {
                        addLog('live', `${station.chargePointId} #${connector.connectorId} transaction `
                            + `${connector.transactionId} started for ${connector.idTag}`, station.chargePointId);
                    } else if (before?.transactionId) {
                        addLog('ok', `${station.chargePointId} #${connector.connectorId} transaction `
                            + `${before.transactionId} ended · register ${formatWh(connector.meterValueWh)}`,
                            station.chargePointId);
                    }
                }
                state.previous.set(id, { status: connector.status, transactionId: connector.transactionId });

                if (connector.status === 'Charging') {
                    const samples = state.history.get(id) ?? [];
                    samples.push(connector.meterValueWh);
                    while (samples.length > HISTORY_POINTS) {
                        samples.shift();
                    }
                    state.history.set(id, samples);
                }
            }
        }
        for (const id of [...state.previous.keys()]) {
            if (!seen.has(id)) {
                state.previous.delete(id);
                state.history.delete(id);
            }
        }
    }

    const poll = setInterval(() => {
        if (!document.hidden) {
            refresh();
        }
    }, POLL_MS);

    document.addEventListener('visibilitychange', () => {
        if (!document.hidden) {
            refresh();
        }
    });

    // Back and forward move between the fleet and the control rooms the user visited.
    window.addEventListener('popstate', () => {
        if (!applyRoute() && state.view !== 'fleet') {
            switchView('fleet');
        }
    });

    // session timers tick locally, independent of the polling interval
    setInterval(() => {
        document.querySelectorAll('[data-since]').forEach((node) => {
            node.textContent = sessionTime(node.dataset.since);
        });
    }, 1000);

    window.addEventListener('beforeunload', () => clearInterval(poll));

    /* -------------------------------------------------------------- rendering */

    const selectedStation = () => state.stations.find((station) => station.chargePointId === state.selectedId)
        ?? state.stations[0]
        ?? null;

    function render() {
        const online = state.stations.filter((station) => station.connected).length;
        const charging = state.stations.reduce((sum, station) => sum + chargingCount(station), 0);
        dom.metrics.querySelector('[data-metric="stations"]').textContent = state.stations.length;
        const onlineNode = dom.metrics.querySelector('[data-metric="online"]');
        onlineNode.textContent = online;
        onlineNode.dataset.tone = online > 0 ? 'live' : '';
        dom.metrics.querySelector('[data-metric="charging"]').textContent = charging;
        dom.metrics.querySelector('[data-metric="energy"]')
            .textContent = formatKwh(state.stations.reduce((sum, station) => sum + totalWh(station), 0));

        dom.banner.hidden = !state.apiDown;
        dom.tabStation.disabled = !state.stations.length;
        dom.fleet.hidden = state.view !== 'fleet';
        dom.station.hidden = state.view !== 'station';
        renderFleetLogToggle();

        if (state.view === 'fleet') {
            renderFleet();
        } else {
            renderControl();
        }
        syncLog();
    }

    /* ----------------------------------------------------------------- fleet */

    function renderFleet() {
        const filter = state.filter.trim().toLowerCase();
        const visible = state.stations.filter((station) => !filter
            || station.chargePointId.toLowerCase().includes(filter)
            || station.webSocketUrl.toLowerCase().includes(filter));

        dom.fleetSub.textContent = state.stations.length
            ? `${visible.length} of ${state.stations.length} charge points · refreshing every ${POLL_MS / 1000} s`
            : 'Nothing registered yet';
        dom.fleetEmpty.hidden = visible.length > 0;
        dom.emptyTitle.textContent = state.stations.length ? 'No charge point matches the filter' : 'No charge points yet';
        dom.emptyBody.textContent = state.stations.length
            ? 'Clear the filter to see the whole fleet.'
            : 'Register a charge point to open an OCPP session against your central system.';

        const renderedIds = [...dom.grid.children].map((card) => card.dataset.id);
        const wantedIds = visible.map((station) => station.chargePointId);
        if (renderedIds.join('|') !== wantedIds.join('|')) {
            dom.grid.innerHTML = visible.map(stationCard).join('');
        }
        // patched either way, so a freshly inserted card is never a poll behind
        visible.forEach((station, index) => updateStationCard(dom.grid.children[index], station));
        pushRoute(null);
    }

    function connectorChips(station) {
        return station.connectors.map((connector) => `
            <span class="chip" data-status="${escapeHtml(connector.status)}">
                #${connector.connectorId} ${escapeHtml(connector.status)}
                ${connector.meterValueWh > 0 ? `· ${formatKwh(connector.meterValueWh)}` : ''}
                ${limitNote(connector)}
            </span>`).join('');
    }

    /**
     * The power a charging profile allows, shown as a suffix on the connector chip. Central systems
     * that use smart charging look for this, and a suspended connector is worth calling out.
     */
    function limitNote(connector) {
        if (connector.suspended) {
            return '· suspended by profile';
        }
        if (connector.chargingLimitW === null || connector.chargingLimitW === undefined) {
            return '';
        }
        return `· ${formatPower(connector.chargingLimitW)} limit`;
    }

    function stationCard(station) {
        return `
        <article class="card" data-id="${escapeHtml(station.chargePointId)}">
            <button type="button" class="card__open" data-action="control"
                    data-id="${escapeHtml(station.chargePointId)}"
                    aria-label="Open the control view for ${escapeHtml(station.chargePointId)}"
                    title="${escapeHtml(station.webSocketUrl)}"></button>
            <header class="card__head">
                <div class="card__titles">
                    <div class="card__id">${escapeHtml(station.chargePointId)}</div>
                    <div class="card__url" title="${escapeHtml(station.webSocketUrl)}">${escapeHtml(station.webSocketUrl)}</div>
                </div>
                <div class="card__badges">
                    ${station.authenticated ? '<span class="chip" data-tone="accent">basic auth</span>' : ''}
                    <span class="chip" data-field="connection" data-tone="${station.connected ? 'ok' : 'off'}">
                        ${station.connected ? 'online' : 'offline'}</span>
                </div>
            </header>
            <dl class="card__meta">
                <div><dt>Power</dt><dd data-field="power">${formatPower(station.chargingPower)}</dd></div>
                <div><dt>Interval</dt><dd data-field="interval">${station.meterValuesFrequency} s</dd></div>
                <div><dt>Metered</dt><dd data-field="metered">${formatKwh(totalWh(station))}</dd></div>
            </dl>
            <div class="card__connectors" data-field="connectors">${connectorChips(station)}</div>
            <p class="card__error" data-field="error" ${station.lastError ? '' : 'hidden'}>
                <span aria-hidden="true">▲</span><span data-field="error-text">${escapeHtml(station.lastError ?? '')}</span>
            </p>
            <footer class="card__foot">
                <button type="button" class="btn btn--sm" data-action="control"
                        data-id="${escapeHtml(station.chargePointId)}">Control</button>
                <button type="button" class="btn btn--sm" data-action="toggle" data-field="toggle"
                        data-id="${escapeHtml(station.chargePointId)}">${station.connected ? 'Disconnect' : 'Connect'}</button>
                <button type="button" class="btn btn--sm" data-action="remove" data-field="remove"
                        data-id="${escapeHtml(station.chargePointId)}">Remove</button>
            </footer>
        </article>`;
    }

    function updateStationCard(card, station) {
        const charging = chargingCount(station);
        card.classList.toggle('is-charging', charging > 0);
        card.classList.toggle('is-online', charging === 0 && station.connected);
        card.classList.toggle('is-offline', !station.connected);

        const connection = card.querySelector('[data-field="connection"]');
        connection.textContent = station.connected ? 'online' : 'offline';
        connection.dataset.tone = station.connected ? 'ok' : 'off';

        card.querySelector('[data-field="power"]').textContent = formatPower(station.chargingPower);
        card.querySelector('[data-field="interval"]').textContent = `${station.meterValuesFrequency} s`;
        card.querySelector('[data-field="metered"]').textContent = formatKwh(totalWh(station));
        card.querySelector('[data-field="connectors"]').innerHTML = connectorChips(station);

        const error = card.querySelector('[data-field="error"]');
        error.hidden = !station.lastError;
        error.querySelector('[data-field="error-text"]').textContent = station.lastError ?? '';

        const toggle = card.querySelector('[data-field="toggle"]');
        toggle.textContent = station.connected ? 'Disconnect' : 'Connect';
        toggle.disabled = state.busy.has(`connect:${station.chargePointId}`)
            || state.busy.has(`disconnect:${station.chargePointId}`);

        const remove = card.querySelector('[data-field="remove"]');
        const confirming = state.confirming.has(station.chargePointId);
        remove.textContent = confirming ? 'Confirm' : 'Remove';
        remove.classList.toggle('btn--danger', confirming);
    }

    /* ---------------------------------------------------------- control room */

    function renderControl() {
        const station = selectedStation();
        if (!station) {
            dom.stationTitle.textContent = 'No charge point selected';
            return;
        }
        if (state.selectedId !== station.chargePointId) {
            state.selectedId = station.chargePointId;
            localStorage.setItem('ocpp.selected', station.chargePointId);
        }
        pushRoute(station.chargePointId);

        const options = state.stations.map((candidate) => `
            <option value="${escapeHtml(candidate.chargePointId)}"
                ${candidate.chargePointId === station.chargePointId ? 'selected' : ''}>
                ${escapeHtml(candidate.chargePointId)}</option>`).join('');
        if (dom.picker.innerHTML !== options) {
            dom.picker.innerHTML = options;
        }

        const charging = chargingCount(station);
        dom.stationTitle.textContent = station.chargePointId;
        dom.stationSub.textContent = `${station.connectors.length} connector${station.connectors.length === 1 ? '' : 's'}`
            + ` · ${formatPower(station.chargingPower)} · metering every ${station.meterValuesFrequency} s`
            + (charging ? ` · ${charging} charging` : '');
        dom.stationState.textContent = station.connected ? 'online' : 'offline';
        dom.stationState.dataset.tone = station.connected ? 'ok' : 'off';

        dom.stationKv.innerHTML = `
            <div><dt>Central system</dt><dd class="wrap-mono">${escapeHtml(station.centralSystemUrl)}</dd></div>
            <div><dt>Authentication</dt><dd>${station.authenticated ? 'HTTP Basic' : 'anonymous'}</dd></div>
            <div><dt>Connectors</dt><dd>${station.connectors.map((connector) => connector.connectorId).join(', ')}</dd></div>
            <div><dt>Metered</dt><dd>${formatKwh(totalWh(station))}</dd></div>
            <div><dt>Last error</dt><dd class="${station.lastError ? 'is-error' : 'is-muted'}">
                ${escapeHtml(station.lastError ?? 'none')}</dd></div>`;

        dom.stationToggle.textContent = station.connected ? 'Disconnect' : 'Connect';
        dom.stationToggle.disabled = state.busy.has(`connect:${station.chargePointId}`)
            || state.busy.has(`disconnect:${station.chargePointId}`);
        const confirming = state.confirming.has(station.chargePointId);
        dom.stationRemove.textContent = confirming ? 'Confirm remove' : 'Remove';
        dom.stationRemove.classList.toggle('btn--danger', confirming);

        const renderedKeys = [...dom.connectors.children].map((panel) => panel.dataset.key);
        const wantedKeys = station.connectors.map((connector) => connectorKey(station.chargePointId, connector.connectorId));
        if (renderedKeys.join('|') !== wantedKeys.join('|')) {
            dom.connectors.innerHTML = station.connectors.map((connector) => connectorPanel(station, connector)).join('');
        }
        // patched either way: a freshly inserted panel gets its timer, buttons and trend set here
        station.connectors.forEach((connector, index) =>
            updateConnectorPanel(dom.connectors.children[index], station, connector));
    }

    const canPlugIn = (status) => status === 'Available' || status === 'Preparing' || status === 'Finishing';
    const canPlugOut = (status) => status === 'Charging' || status === 'Preparing' || status === 'Finishing';

    function gaugeOffset(powerW) {
        return (GAUGE_CIRCUMFERENCE * (1 - Math.min(1, powerW / POWER_SCALE_W))).toFixed(1);
    }

    function connectorPanel(station, connector) {
        const powerW = connector.status === 'Charging' ? station.chargingPower : 0;
        const tag = dom.rfidTag.value.trim() || 'tag1';
        const id = connectorKey(station.chargePointId, connector.connectorId);

        return `
        <article class="connector" data-key="${escapeHtml(id)}" data-state="${escapeHtml(connector.status)}"
                 data-cp="${escapeHtml(station.chargePointId)}" data-connector="${connector.connectorId}">
            <header class="connector__head">
                <h3>
                    <span class="connector__id">#${connector.connectorId}</span>
                    <span class="status" data-field="status" data-status="${escapeHtml(connector.status)}">
                        <i></i><span data-field="status-text">${escapeHtml(connector.status)}</span></span>
                </h3>
                <span class="connector__tx" data-field="tx">
                    ${connector.transactionId ? `tx ${connector.transactionId}` : 'no transaction'}</span>
            </header>
            <div class="connector__body">
                <div class="gauge">
                    <svg class="gauge__svg" viewBox="0 0 120 120" role="img" data-field="gauge-label"
                         aria-label="power ${(powerW / 1000).toFixed(1)} of 22 kilowatts">
                        <circle class="gauge__track" cx="60" cy="60" r="${GAUGE_RADIUS}"></circle>
                        <circle class="gauge__value" cx="60" cy="60" r="${GAUGE_RADIUS}" data-field="gauge"
                                stroke-dasharray="${GAUGE_CIRCUMFERENCE.toFixed(1)}"
                                stroke-dashoffset="${gaugeOffset(powerW)}"></circle>
                    </svg>
                    <div class="gauge__center">
                        <span class="gauge__number" data-field="kw">${(powerW / 1000).toFixed(1)}</span>
                        <span class="gauge__unit">kW</span>
                    </div>
                </div>
                <dl class="readout">
                    <div><dt>Energy</dt><dd data-field="energy">${formatKwh(connector.meterValueWh)}</dd></div>
                    <div><dt>Session</dt><dd class="mono" data-field="session">—</dd></div>
                    <div><dt>Register</dt><dd data-field="register">${formatWh(connector.meterValueWh)}</dd></div>
                    <div><dt>Rated</dt><dd>${formatPower(station.chargingPower)}</dd></div>
                    <div><dt>id tag</dt><dd data-field="tag"
                        class="${connector.idTag ? 'mono' : 'is-empty'}">${escapeHtml(connector.idTag ?? 'none')}</dd></div>
                </dl>
                <div class="spark" data-field="spark">${sparkline(state.history.get(id) ?? [])}</div>
            </div>
            <footer class="connector__foot">
                <button type="button" class="btn btn--sm" data-act="plug-in" data-field="plug-in">Plug in</button>
                <button type="button" class="btn btn--sm btn--accent" data-act="rfid" data-field="rfid">Tap ${escapeHtml(tag)}</button>
                <button type="button" class="btn btn--sm" data-act="plug-out" data-field="plug-out">Plug out</button>
            </footer>
        </article>`;
    }

    function updateConnectorPanel(panel, station, connector) {
        const powerW = connector.status === 'Charging' ? station.chargingPower : 0;
        const tag = dom.rfidTag.value.trim() || 'tag1';
        const id = connectorKey(station.chargePointId, connector.connectorId);
        const busy = [...state.busy].some((entry) => entry.endsWith(id));

        panel.dataset.state = connector.status;
        panel.dataset.connector = connector.connectorId;

        const status = panel.querySelector('[data-field="status"]');
        status.dataset.status = connector.status;
        status.querySelector('[data-field="status-text"]').textContent = connector.status;
        panel.querySelector('[data-field="tx"]').textContent = connector.transactionId
            ? `tx ${connector.transactionId}`
            : 'no transaction';

        panel.querySelector('[data-field="gauge"]').setAttribute('stroke-dashoffset', gaugeOffset(powerW));
        panel.querySelector('[data-field="gauge-label"]')
            .setAttribute('aria-label', `power ${(powerW / 1000).toFixed(1)} of 22 kilowatts`);
        panel.querySelector('[data-field="kw"]').textContent = (powerW / 1000).toFixed(1);

        panel.querySelector('[data-field="energy"]').textContent = formatKwh(connector.meterValueWh);
        panel.querySelector('[data-field="register"]').textContent = formatWh(connector.meterValueWh);
        const tagNode = panel.querySelector('[data-field="tag"]');
        tagNode.textContent = connector.idTag ?? 'none';
        tagNode.classList.toggle('is-empty', !connector.idTag);

        const session = panel.querySelector('[data-field="session"]');
        if (connector.transactionStartedAt) {
            session.dataset.since = connector.transactionStartedAt;
            session.textContent = sessionTime(connector.transactionStartedAt);
        } else {
            delete session.dataset.since;
            session.textContent = '—';
        }

        panel.querySelector('[data-field="spark"]').innerHTML = sparkline(state.history.get(id) ?? []);

        const disabled = {
            'plug-in': busy || !canPlugIn(connector.status),
            rfid: busy,
            'plug-out': busy || !canPlugOut(connector.status)
        };
        Object.entries(disabled).forEach(([action, isDisabled]) => {
            const button = panel.querySelector(`[data-field="${action}"]`);
            button.disabled = isDisabled;
            button.dataset.tag = tag;
        });
        panel.querySelector('[data-field="rfid"]').textContent = `Tap ${tag}`;
    }

    function sparkline(samples) {
        if (samples.length < 3) {
            return '';
        }
        const min = Math.min(...samples);
        const max = Math.max(...samples, min + 1);
        const step = 100 / (samples.length - 1);
        const points = samples.map((wh, index) =>
            `${(index * step).toFixed(2)},${(24 - ((wh - min) / (max - min)) * 22).toFixed(2)}`).join(' ');
        return `<svg viewBox="0 0 100 26" preserveAspectRatio="none" aria-hidden="true">`
            + `<polyline points="${points}"></polyline></svg>`;
    }

    /* ---------------------------------------------------------------- dialog */

    let dialogMode = 'create';

    function openDialog(mode, station) {
        dialogMode = mode;
        dom.formError.hidden = true;
        dom.form.reset();
        dom.formTitle.textContent = mode === 'create' ? 'Add charge point' : `Edit ${station.chargePointId}`;
        dom.formSubmit.textContent = mode === 'create' ? 'Create' : 'Save changes';
        field('chargePointId').readOnly = mode === 'edit';

        if (station) {
            field('chargePointId').value = station.chargePointId;
            field('centralSystemUrl').value = station.centralSystemUrl;
            field('chargingPower').value = station.chargingPower;
            field('meterValuesFrequency').value = station.meterValuesFrequency;
            field('connectorIds').value = station.connectors.map((connector) => connector.connectorId).join(', ');
            field('connect').checked = station.connected;
        } else {
            field('connect').checked = true;
        }
        updatePreview();
        dom.dialog.showModal();
        (mode === 'create' ? field('chargePointId') : field('chargingPower')).focus();
    }

    function readForm() {
        return {
            chargePointId: field('chargePointId').value.trim(),
            centralSystemUrl: field('centralSystemUrl').value.trim() || null,
            connectorIds: field('connectorIds').value.split(',')
                .map((part) => part.trim()).filter(Boolean).map(Number),
            chargingPower: field('chargingPower').value ? Number(field('chargingPower').value) : null,
            meterValuesFrequency: field('meterValuesFrequency').value ? Number(field('meterValuesFrequency').value) : null,
            username: field('username').value.trim() || null,
            password: field('password').value || null,
            connect: field('connect').checked
        };
    }

    function validate(form) {
        if (!form.chargePointId) return 'A charge point id is required.';
        if (/[/\s]/.test(form.chargePointId)) return 'The charge point id must not contain slashes or spaces.';
        if (form.centralSystemUrl && !/^wss?:\/\//.test(form.centralSystemUrl)) {
            return 'The central system URL must start with ws:// or wss://.';
        }
        if (!form.connectorIds.length) return 'At least one connector id is required.';
        if (form.connectorIds.some((id) => !Number.isInteger(id) || id < 1)) {
            return 'Connector ids must be whole numbers greater than zero.';
        }
        if (new Set(form.connectorIds).size !== form.connectorIds.length) return 'Connector ids must be unique.';
        if (form.chargingPower !== null && !(form.chargingPower > 0)) return 'Charging power must be greater than 0 W.';
        if (form.meterValuesFrequency !== null && !(form.meterValuesFrequency > 0)) {
            return 'The meter interval must be greater than 0 s.';
        }
        if ((form.username === null) !== (form.password === null)) {
            return 'Fill in both the username and the password, or neither.';
        }
        return null;
    }

    function updatePreview() {
        const form = readForm();
        const station = dialogMode === 'edit' ? selectedStation() : null;
        const base = form.centralSystemUrl ?? station?.centralSystemUrl ?? 'ws://localhost:8080';
        const power = form.chargingPower ?? station?.chargingPower ?? 5000;
        const frequency = form.meterValuesFrequency ?? station?.meterValuesFrequency ?? 60;
        const lines = [
            `session → <b>${escapeHtml(base.replace(/\/$/, ''))}/${escapeHtml(form.chargePointId || '…')}</b>`,
            `metering → <b>${Math.round(power * frequency / 3600)} Wh</b> per message (${power} W × ${frequency} s / 3600)`
        ];
        if (form.username) {
            lines.push(`handshake → <b>HTTP Basic</b> as ${escapeHtml(form.username)}`);
        }
        if (station?.authenticated && !form.username) {
            lines.push('<span class="is-bad">credentials of this charge point will be removed</span>');
        }
        dom.preview.innerHTML = lines.join('<br>');
    }

    async function submitForm(event) {
        event.preventDefault();
        const form = readForm();
        const problem = validate(form);
        if (problem) {
            dom.formError.hidden = false;
            dom.formError.textContent = problem;
            return;
        }
        dom.formError.hidden = true;
        dom.formSubmit.disabled = true;
        const payload = { ...form, centralSystemUrl: form.centralSystemUrl ?? undefined };
        const result = dialogMode === 'create'
            ? await createStation(payload)
            : await updateStation(form.chargePointId, payload);
        dom.formSubmit.disabled = false;
        if (result) {
            dom.dialog.close();
            state.selectedId = form.chargePointId;
            refresh();
        }
    }

    /* ---------------------------------------------------------------- events */

    function requestRemoval(chargePointId) {
        if (state.confirming.has(chargePointId)) {
            state.confirming.delete(chargePointId);
            removeStation(chargePointId).then(() => {
                if (state.selectedId === chargePointId) {
                    state.selectedId = null;
                }
            });
            return;
        }
        // two step confirmation in place of a native dialog
        state.confirming.add(chargePointId);
        render();
        setTimeout(() => {
            if (state.confirming.delete(chargePointId)) {
                render();
            }
        }, CONFIRM_TIMEOUT_MS);
    }

    /* ----------------------------------------------------------------- routes */

    /**
     * Where the console is served from. The assets and the REST API are addressed from the application
     * root rather than relative to the page, so a deep route cannot shift them; the routes below are
     * built from the same root.
     */
    const APP_ROOT = '/';

    /** Path segment that introduces the control room of one charge point: /station/<cpId>. */
    const ROUTE_SEGMENT = 'station';

    const stationPath = (chargePointId) =>
        `${APP_ROOT}${ROUTE_SEGMENT}/${encodeURIComponent(chargePointId)}`;

    /** @return the charge point the address bar asks for, null when it points at the fleet. */
    function routeStationId() {
        const [segment, chargePointId] = location.pathname.slice(APP_ROOT.length).split('/').filter(Boolean);
        return segment === ROUTE_SEGMENT && chargePointId ? decodeURIComponent(chargePointId) : null;
    }

    // Read before the first render, which writes the fleet into the address bar.
    let deepLink = routeStationId();
    // The address bar is only written once a deep link had its chance to be applied.
    let routeReady = false;

    /** Keeps the address bar in step with the view, without stacking up history entries. */
    function pushRoute(chargePointId) {
        const target = chargePointId ? stationPath(chargePointId) : APP_ROOT;
        if (routeReady && location.pathname !== target) {
            history.pushState(null, '', target);
        }
    }

    /** Repairs a link that cannot be honoured any more, in place instead of adding an entry. */
    function replaceRoute(chargePointId) {
        const target = chargePointId ? stationPath(chargePointId) : APP_ROOT;
        if (location.pathname !== target) {
            history.replaceState(null, '', target);
        }
    }

    /**
     * Applies the address bar: {@code /station/<cpId>} opens that station's control room. Called once
     * after the first load and on every back/forward, so links and the history buttons both work.
     *
     * @return whether a station was opened; false means the fleet stays on screen
     */
    function applyRoute() {
        const chargePointId = deepLink ?? routeStationId();
        deepLink = null;
        routeReady = true;
        if (!chargePointId) {
            return false;
        }
        if (!state.stations.some((station) => station.chargePointId === chargePointId)) {
            addLog('warn', `The address bar asked for charge point '${chargePointId}', `
                + 'which this simulator does not have.');
            replaceRoute(null);
            return false;
        }
        state.selectedId = chargePointId;
        switchView('station');
        return true;
    }

    function switchView(view) {
        state.view = view;
        document.querySelectorAll('.tab').forEach((node) => {
            const active = node.dataset.view === view;
            node.classList.toggle('is-active', active);
            node.setAttribute('aria-current', active ? 'page' : 'false');
        });
        render();
    }

    /**
     * Leaves the control room. The back buttons and the Escape key live inside the view that gets
     * hidden, so focus is handed to the Fleet tab, which stays visible and means the same thing.
     */
    function showFleet() {
        switchView('fleet');
        dom.tabFleet.focus();
    }

    document.addEventListener('click', (event) => {
        const actionNode = event.target.closest('[data-action]');
        if (actionNode) {
            const { action, id } = actionNode.dataset;
            if (action === 'open-create') {
                openDialog('create');
            } else if (action === 'control') {
                state.selectedId = id;
                switchView('station');
            } else if (action === 'back') {
                showFleet();
            } else if (action === 'toggle') {
                const station = state.stations.find((entry) => entry.chargePointId === id);
                if (station) {
                    station.connected ? disconnectStation(id) : connectStation(id);
                }
            } else if (action === 'remove') {
                requestRemoval(id);
            }
            return;
        }

        const actNode = event.target.closest('[data-act]');
        if (actNode) {
            const panel = actNode.closest('[data-cp]');
            const station = state.stations.find((entry) => entry.chargePointId === panel?.dataset.cp);
            if (station && panel) {
                connectorAction(station, Number(panel.dataset.connector), actNode.dataset.act, actNode.dataset.tag);
            }
            return;
        }

        const tab = event.target.closest('[data-view]');
        if (tab) {
            switchView(tab.dataset.view);
        }
    });

    dom.filter.addEventListener('input', () => {
        state.filter = dom.filter.value;
        renderFleet();
    });

    dom.picker.addEventListener('change', () => {
        state.selectedId = dom.picker.value;
        localStorage.setItem('ocpp.selected', state.selectedId);
        // render(), not renderControl(): the activity log is scoped to the selected station too.
        render();
    });

    // Escape leaves the control room, unless it is closing the station dialog instead.
    document.addEventListener('keydown', (event) => {
        if (event.key === 'Escape' && state.view === 'station' && !dom.dialog.open) {
            showFleet();
        }
    });

    dom.rfidTag.addEventListener('change', () => {
        renderControl();
        addLog('info', `card to tap set to ${dom.rfidTag.value.trim() || 'tag1'}`);
    });

    dom.stationEdit.addEventListener('click', () => {
        const station = selectedStation();
        if (station) {
            openDialog('edit', station);
        }
    });

    dom.stationToggle.addEventListener('click', () => {
        const station = selectedStation();
        if (station) {
            station.connected ? disconnectStation(station.chargePointId) : connectStation(station.chargePointId);
        }
    });

    dom.stationRemove.addEventListener('click', () => {
        const station = selectedStation();
        if (station) {
            requestRemoval(station.chargePointId);
        }
    });

    // Clearing is scoped like the list itself: this button leaves other stations' history alone.
    dom.clearStationLog.addEventListener('click', () => {
        state.log = state.log.filter((entry) => !entry.chargePointId || entry.chargePointId !== state.selectedId);
        renderLog();
    });

    dom.clearFleetLog.addEventListener('click', () => {
        state.log = [];
        renderLog();
    });

    dom.refreshStationLog.addEventListener('click', (event) => pullLatest(event.currentTarget));
    dom.refreshFleetLog.addEventListener('click', (event) => pullLatest(event.currentTarget));
    dom.refreshStation.addEventListener('click', (event) => pullLatest(event.currentTarget));
    dom.refreshFleet.addEventListener('click', (event) => pullLatest(event.currentTarget));
    dom.exportStationLog.addEventListener('click', () => exportLog(state.selectedId));
    dom.exportFleetLog.addEventListener('click', () => exportLog(null));
    dom.toggleFleetLog.addEventListener('click', () => setFleetLogVisible(!state.showFleetLog));

    ['chargePointId', 'centralSystemUrl', 'chargingPower', 'meterValuesFrequency',
        'connectorIds', 'username', 'password']
        .forEach((name) => field(name).addEventListener('input', updatePreview));
    field('connect').addEventListener('change', updatePreview);
    dom.form.addEventListener('submit', submitForm);
    $('#form-cancel').addEventListener('click', () => dom.dialog.close());
    $('#form-close').addEventListener('click', () => dom.dialog.close());

    dom.rfidTag.value = state.tags[0] ?? 'tag1';
    document.querySelectorAll('[data-year]').forEach((node) => {
        node.textContent = new Date().getFullYear();
    });
    addLog('info', `console ready — polling the simulator every ${POLL_MS / 1000} s`);
    // the first pull decides whether the address bar asks for a station of its own
    refresh().then(applyRoute);
})();
