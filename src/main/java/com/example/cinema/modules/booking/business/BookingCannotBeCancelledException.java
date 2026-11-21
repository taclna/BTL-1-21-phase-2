package com.example.cinema.modules.booking.business;

import java.util.UUID;

public class BookingCannotBeCancelledException extends RuntimeException {

    public BookingCannotBeCancelledException(UUID bookingId) {
        super("Booking cannot be cancelled: " + bookingId);
    }
}
