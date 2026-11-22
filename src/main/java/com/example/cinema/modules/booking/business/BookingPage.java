package com.example.cinema.modules.booking.business;

import java.util.List;

public record BookingPage(List<Booking> items, long totalItems) {
}
