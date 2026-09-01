package com.aetherops.oncall;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class OnCallRotationTest {

    private OnCallRotation rotation(List<String> participants, List<UUID> userIds) {
        return OnCallRotation.builder()
            .id(1L).tenantId(UUID.randomUUID()).serviceId("db-svc").rotationName("DB Team")
            .rotationStartAt(Instant.now().minusSeconds(3600)).shiftLengthHours(1_000_000) // never rotates during the test
            .participants(participants)
            .participantUserIds(userIds)
            .build();
    }

    @Test
    void currentOnCallPhoneNumber_noArgs_unaffectedByAvailabilityConcept() {
        OnCallRotation r = rotation(List.of("+15551111111", "+15552222222"), null);

        assertThat(r.currentOnCallPhoneNumber()).isEqualTo("+15551111111");
    }

    @Test
    void currentOnCallPhoneNumber_predicateAlwaysTrue_matchesNoArgsBehavior() {
        OnCallRotation r = rotation(List.of("+15551111111", "+15552222222"), null);

        assertThat(r.currentOnCallPhoneNumber(userId -> true)).isEqualTo(r.currentOnCallPhoneNumber());
    }

    @Test
    void currentOnCallPhoneNumber_noUserIdMapping_treatsAsAvailable() {
        OnCallRotation r = rotation(List.of("+15551111111", "+15552222222"), null);

        assertThat(r.currentOnCallPhoneNumber(userId -> false)).isEqualTo("+15551111111");
    }

    @Test
    void currentOnCallPhoneNumber_scheduledParticipantUnavailable_skipsToNext() {
        UUID unavailableUser = UUID.randomUUID();
        UUID availableUser = UUID.randomUUID();
        OnCallRotation r = rotation(
            List.of("+15551111111", "+15552222222"),
            List.of(unavailableUser, availableUser));

        Predicate<UUID> isAvailable = userId -> !userId.equals(unavailableUser);

        assertThat(r.currentOnCallPhoneNumber(isAvailable)).isEqualTo("+15552222222");
    }

    @Test
    void currentOnCallPhoneNumber_everyoneUnavailable_fallsBackToOriginallyScheduled() {
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();
        OnCallRotation r = rotation(
            List.of("+15551111111", "+15552222222"),
            List.of(user1, user2));

        assertThat(r.currentOnCallPhoneNumber(userId -> false)).isEqualTo("+15551111111");
    }

    @Test
    void currentOnCallPhoneNumber_partialUserIdMapping_shorterListTreatedAsUnmapped() {
        UUID user1 = UUID.randomUUID();
        OnCallRotation r = rotation(
            List.of("+15551111111", "+15552222222"),
            List.of(user1)); // only first participant has a mapping

        assertThat(r.currentOnCallPhoneNumber(userId -> false)).isEqualTo("+15552222222");
    }

    @Test
    void currentOnCallPhoneNumber_emptyParticipants_returnsNull() {
        OnCallRotation r = rotation(List.of(), null);

        assertThat(r.currentOnCallPhoneNumber(userId -> true)).isNull();
    }
}
