package com.ocpp.chargepointsimulator.registry;

import com.ocpp.chargepointsimulator.domain.ChargePointSession;
import com.ocpp.chargepointsimulator.exceptions.ChargePointAlreadyExistsException;
import com.ocpp.chargepointsimulator.exceptions.ChargePointNotFoundException;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of the charge points this simulator instance runs.
 *
 * <p>Charge points are configured at runtime over HTTP and are not persisted: restarting the
 * application gives an empty registry, plus the optional charge point from the environment.
 */
@Component
public class ChargePointRegistry {

    private final Map<String, ChargePointSession> sessions = new ConcurrentHashMap<>();

    /** @throws ChargePointAlreadyExistsException when the id is already taken. */
    public void add(ChargePointSession session) {
        String chargePointId = session.getChargePointId();
        if (sessions.containsKey(chargePointId)) {
            throw new ChargePointAlreadyExistsException("Charge point '" + chargePointId
                    + "' is already registered. Remove it first or use another chargePointId.");
        }
        sessions.put(chargePointId, session);
    }

    /** @throws ChargePointNotFoundException when no charge point with that id is registered. */
    public ChargePointSession get(String chargePointId) {
        ChargePointSession session = sessions.get(chargePointId);
        if (session == null) {
            throw new ChargePointNotFoundException("No charge point registered with id '" + chargePointId
                    + "'. Registered charge points: " + registeredIds() + ".");
        }
        return session;
    }

    /** @throws ChargePointNotFoundException when no charge point with that id is registered. */
    public ChargePointSession remove(String chargePointId) {
        ChargePointSession session = sessions.remove(chargePointId);
        if (session == null) {
            throw new ChargePointNotFoundException("No charge point registered with id '" + chargePointId
                    + "'. Registered charge points: " + registeredIds() + ".");
        }
        return session;
    }

    /**
     * Swaps a registered charge point for a new session with the same id.
     *
     * @return the session that was replaced
     * @throws ChargePointNotFoundException when there is nothing to replace
     */
    public ChargePointSession replace(ChargePointSession session) {
        ChargePointSession previous = sessions.replace(session.getChargePointId(), session);
        if (previous == null) {
            throw new ChargePointNotFoundException("No charge point registered with id '" + session.getChargePointId()
                    + "'. Registered charge points: " + registeredIds() + ".");
        }
        return previous;
    }

    public Collection<ChargePointSession> all() {
        return List.copyOf(sessions.values());
    }

    public Set<String> registeredIds() {
        return Set.copyOf(sessions.keySet());
    }

    public int size() {
        return sessions.size();
    }
}
