package com.example.cinema.modules.booking.business;

import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface BookingRepository {
    Booking save(Booking booking);
    Optional<Booking> findById(UUID bookingId);
    Optional<Booking> findByIdForUpdate(UUID bookingId);
    Optional<Booking> findByIdAndUserId(UUID bookingId, UUID userId);
    Optional<Booking> findByIdAndUserIdForUpdate(UUID bookingId, UUID userId);
    List<Booking> findAllByUserId(UUID userId);
    BookingPage findAllByUserId(UUID userId, int page, int pageSize);
    BookingPage findAll(int page, int pageSize);
    boolean existsByBookingCode(String bookingCode);
    boolean existsByShowtimeIdAndSeatId(UUID showtimeId, UUID seatId);
    boolean existsConfirmedByShowtimeId(UUID showtimeId);
    List<UUID> findBookedSeatIds(UUID showtimeId);
    void cancelById(UUID bookingId, Instant cancelledAt);
}
