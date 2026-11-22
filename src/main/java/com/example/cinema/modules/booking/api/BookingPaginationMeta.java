package com.example.cinema.modules.booking.api;

public record BookingPaginationMeta(
        int page,
        int pageSize,
        long totalItems,
        long totalPages
) {
}
