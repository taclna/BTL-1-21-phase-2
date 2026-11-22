package com.example.cinema.modules.booking.api;

import com.example.cinema.modules.booking.business.Booking;
import com.example.cinema.modules.booking.business.PaymentStatus;
import com.example.cinema.modules.booking.business.BookingStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class BookingDetailResponse {

    private final UUID bookingId;
    private final String bookingCode;
    private final UUID showtimeId;
    private final List<BookingSeatResponse> seats;
    private final BigDecimal totalAmount;
    private final PaymentStatus paymentStatus;
    private final BookingStatus status;
    private final Instant createdAt;
    private final Instant cancelledAt;

    public BookingDetailResponse(
            UUID bookingId,
            String bookingCode,
            UUID showtimeId,
            List<BookingSeatResponse> seats,
            BigDecimal totalAmount,
            PaymentStatus paymentStatus,
            BookingStatus status,
            Instant createdAt,
            Instant cancelledAt
    ) {
        this.bookingId = bookingId;
        this.bookingCode = bookingCode;
        this.showtimeId = showtimeId;
        this.seats = List.copyOf(seats);
        this.totalAmount = totalAmount;
        this.paymentStatus = paymentStatus;
        this.status = status;
        this.createdAt = createdAt;
        this.cancelledAt = cancelledAt;
    }

    public static BookingDetailResponse from(Booking booking) {
        return new BookingDetailResponse(
                booking.getBookingId(), booking.getBookingCode(), booking.getShowtimeId(),
                booking.getSeats().stream().map(BookingSeatResponse::from).toList(),
                booking.getTotalAmount(), booking.getPaymentStatus(), booking.getStatus(),
                booking.getCreatedAt(), booking.getCancelledAt());
    }

    public UUID getBookingId() { return bookingId; }
    public String getBookingCode() { return bookingCode; }
    public UUID getShowtimeId() { return showtimeId; }
    public List<BookingSeatResponse> getSeats() { return seats; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public PaymentStatus getPaymentStatus() { return paymentStatus; }
    public BookingStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCancelledAt() { return cancelledAt; }
}
