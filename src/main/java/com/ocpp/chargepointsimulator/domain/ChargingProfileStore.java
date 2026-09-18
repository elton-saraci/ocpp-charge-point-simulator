package com.ocpp.chargepointsimulator.domain;

import eu.chargetime.ocpp.model.core.ChargingProfilePurposeType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The charging profiles a charge point holds in its memory.
 *
 * <p>Replacement follows OCPP 1.6: a new profile replaces the stored one with the same
 * {@code chargingProfileId}, and also the profile with the same connector, purpose and
 * {@code stackLevel} — that slot can only hold one profile.
 *
 * <p>All accessors are synchronized for the same reason {@link ConnectorState} is: profiles arrive
 * on the OCPP receiver thread while the metering scheduler and HTTP threads read them.
 */
public class ChargingProfileStore {

    private final Map<Integer, StoredChargingProfile> profiles = new LinkedHashMap<>();

    /**
     * Stores a profile, replacing what occupies its slot.
     *
     * @return the profile that was replaced, empty when the slot was free
     */
    public synchronized Optional<StoredChargingProfile> put(StoredChargingProfile profile) {
        StoredChargingProfile replaced = profiles.put(profile.chargingProfileId(), profile);
        for (StoredChargingProfile other : new ArrayList<>(profiles.values())) {
            if (other.chargingProfileId() != profile.chargingProfileId() && sameSlot(other, profile)) {
                profiles.remove(other.chargingProfileId());
            }
        }
        return Optional.ofNullable(replaced);
    }

    private static boolean sameSlot(StoredChargingProfile left, StoredChargingProfile right) {
        return left.connectorId() == right.connectorId()
                && left.purpose() == right.purpose()
                && left.stackLevel() == right.stackLevel();
    }

    /**
     * Removes every stored profile that matches the criteria, where a {@code null} criterion matches
     * anything — the way {@code ClearChargingProfile} works.
     *
     * @return how many profiles were removed
     */
    public synchronized int clear(Integer chargingProfileId,
                                 Integer connectorId,
                                 ChargingProfilePurposeType purpose,
                                 Integer stackLevel) {
        List<Integer> toRemove = profiles.values().stream()
                .filter(profile -> chargingProfileId == null || profile.chargingProfileId() == chargingProfileId)
                .filter(profile -> connectorId == null || profile.connectorId() == connectorId)
                .filter(profile -> purpose == null || profile.purpose() == purpose)
                .filter(profile -> stackLevel == null || profile.stackLevel() == stackLevel)
                .map(StoredChargingProfile::chargingProfileId)
                .toList();
        toRemove.forEach(profiles::remove);
        return toRemove.size();
    }

    /** @return the profiles that can limit the given connector, connector 0 profiles included. */
    public synchronized List<StoredChargingProfile> forConnector(int connectorId) {
        return profiles.values().stream()
                .filter(profile -> profile.appliesTo(connectorId))
                .sorted(Comparator.comparingInt(StoredChargingProfile::chargingProfileId))
                .toList();
    }

    public synchronized List<StoredChargingProfile> all() {
        return List.copyOf(profiles.values());
    }

    /** Replaces the whole content, used when a charge point is redefined and keeps its profiles. */
    public synchronized void replaceAll(Collection<StoredChargingProfile> newProfiles) {
        profiles.clear();
        newProfiles.forEach(profile -> profiles.put(profile.chargingProfileId(), profile));
    }

    public synchronized boolean isEmpty() {
        return profiles.isEmpty();
    }

    public synchronized int size() {
        return profiles.size();
    }
}
