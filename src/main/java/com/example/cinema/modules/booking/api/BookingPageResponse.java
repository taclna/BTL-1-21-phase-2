package com.example.cinema.modules.booking.api;

import java.util.List;

public record BookingPageResponse(
        List<BookingSummaryResponse> data,
        BookingPaginationMeta meta
) {
}
