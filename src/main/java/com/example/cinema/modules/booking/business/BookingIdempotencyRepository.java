package com.example.cinema.modules.booking.business;

import java.util.Optional;
import java.util.UUID;

public interface BookingIdempotencyRepository {

    boolean claim(UUID userId, UUID idempotencyKey, String requestHash);

    Optional<BookingIdempotency> find(UUID userId, UUID idempotencyKey);

    void attachBooking(
            UUID userId,
            UUID idempotencyKey,
            String requestHash,
            UUID bookingId
    );
}
