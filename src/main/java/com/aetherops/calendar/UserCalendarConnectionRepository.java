package com.aetherops.calendar;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserCalendarConnectionRepository extends JpaRepository<UserCalendarConnection, UUID> {

    Optional<UserCalendarConnection> findByUserIdAndProvider(UUID userId, CalendarProvider provider);

    List<UserCalendarConnection> findByUserId(UUID userId);
}
