package com.example.cinema.modules.booking.business;

import java.util.UUID;

public record BookingIdempotency(String requestHash, UUID bookingId) {
}
