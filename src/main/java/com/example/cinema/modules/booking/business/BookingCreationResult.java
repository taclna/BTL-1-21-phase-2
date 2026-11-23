package com.example.cinema.modules.booking.business;

public record BookingCreationResult(Booking booking, boolean replayed) {

    public static BookingCreationResult created(Booking booking) {
        return new BookingCreationResult(booking, false);
    }

    public static BookingCreationResult replayed(Booking booking) {
        return new BookingCreationResult(booking, true);
    }
}
