package com.example.cinema.modules.booking.api;

import com.example.cinema.modules.booking.business.Booking;
import com.example.cinema.modules.booking.business.BookingStatus;
import java.time.Instant;
import java.util.UUID;

public class BookingSummaryResponse {

    private final UUID bookingId;
    private final String bookingCode;
    private final BookingStatus status;
    private final Instant createdAt;
    private final Instant cancelledAt;

    public BookingSummaryResponse(UUID bookingId, String bookingCode, BookingStatus status, Instant createdAt, Instant cancelledAt) {
        this.bookingId = bookingId;
        this.bookingCode = bookingCode;
        this.status = status;
        this.createdAt = createdAt;
        this.cancelledAt = cancelledAt;
    }

    public static BookingSummaryResponse from(Booking booking) {
        return new BookingSummaryResponse(
                booking.getBookingId(), booking.getBookingCode(), booking.getStatus(), booking.getCreatedAt(), booking.getCancelledAt());
    }

    public UUID getBookingId() { return bookingId; }
    public String getBookingCode() { return bookingCode; }
    public BookingStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCancelledAt() { return cancelledAt; }
}
