package com.example.cinema.modules.booking.business;

public class SeatAlreadyBookedException extends RuntimeException {

    public SeatAlreadyBookedException() {
        super("Seat has already been booked");
    }
}
