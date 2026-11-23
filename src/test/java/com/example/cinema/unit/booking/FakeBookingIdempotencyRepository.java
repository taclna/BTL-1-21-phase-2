package com.example.cinema.unit.booking;

import com.example.cinema.modules.booking.business.BookingIdempotency;
import com.example.cinema.modules.booking.business.BookingIdempotencyRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class FakeBookingIdempotencyRepository implements BookingIdempotencyRepository {

    private final Map<Key, BookingIdempotency> records = new HashMap<>();

    @Override
    public boolean claim(UUID userId, UUID idempotencyKey, String requestHash) {
        Key key = new Key(userId, idempotencyKey);
        if (records.containsKey(key)) {
            return false;
        }
        records.put(key, new BookingIdempotency(requestHash, null));
        return true;
    }

    @Override
    public Optional<BookingIdempotency> find(UUID userId, UUID idempotencyKey) {
        return Optional.ofNullable(records.get(new Key(userId, idempotencyKey)));
    }

    @Override
    public void attachBooking(UUID userId, UUID idempotencyKey, String requestHash, UUID bookingId) {
        Key key = new Key(userId, idempotencyKey);
        BookingIdempotency record = records.get(key);
        if (record == null || record.bookingId() != null || !record.requestHash().equals(requestHash)) {
            throw new IllegalStateException("Invalid fake idempotency claim");
        }
        records.put(key, new BookingIdempotency(requestHash, bookingId));
    }

    private record Key(UUID userId, UUID idempotencyKey) {
    }
}
