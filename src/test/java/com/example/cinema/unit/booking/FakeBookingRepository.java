package com.example.cinema.unit.booking;

import com.example.cinema.modules.booking.business.Booking;
import com.example.cinema.modules.booking.business.BookingPage;
import com.example.cinema.modules.booking.business.BookingRepository;
import com.example.cinema.modules.booking.business.BookingStatus;
import com.example.cinema.modules.booking.business.bookingSeat;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

class FakeBookingRepository implements BookingRepository {

    private final Map<UUID, Booking> bookingsById = new HashMap<>();
    private final Map<SeatKey, UUID> activeBookingBySeat = new HashMap<>();

    @Override
    public Booking save(Booking booking) {
        Booking previousBooking = bookingsById.put(booking.getBookingId(), booking);
        if (previousBooking != null) {
            removeActiveSeats(previousBooking);
        }

        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            for (bookingSeat seat : booking.getSeats()) {
                activeBookingBySeat.put(
                        new SeatKey(booking.getShowtimeId(), seat.getSeatId()),
                        booking.getBookingId()
                );
            }
        }
        return booking;
    }

    @Override
    public Optional<Booking> findById(UUID bookingId) {
        return Optional.ofNullable(bookingsById.get(bookingId));
    }

    @Override
    public Optional<Booking> findByIdForUpdate(UUID bookingId) {
        return findById(bookingId);
    }

    @Override
    public Optional<Booking> findByIdAndUserId(UUID bookingId, UUID userId) {
        return findById(bookingId).filter(booking -> booking.getUserId().equals(userId));
    }

    @Override
    public Optional<Booking> findByIdAndUserIdForUpdate(UUID bookingId, UUID userId) {
        return findByIdAndUserId(bookingId, userId);
    }

    @Override
    public java.util.List<Booking> findAllByUserId(UUID userId) {
        return bookingsById.values().stream()
                .filter(booking -> booking.getUserId().equals(userId))
                .toList();
    }

    @Override
    public BookingPage findAllByUserId(UUID userId, int page, int pageSize) {
        java.util.List<Booking> items = bookingsById.values().stream()
                .filter(booking -> booking.getUserId().equals(userId))
                .sorted(java.util.Comparator.comparing(Booking::getCreatedAt).reversed())
                .skip((long) (page - 1) * pageSize)
                .limit(pageSize)
                .toList();
        long totalItems = bookingsById.values().stream()
                .filter(booking -> booking.getUserId().equals(userId))
                .count();
        return new BookingPage(items, totalItems);
    }

    @Override
    public BookingPage findAll(int page, int pageSize) {
        java.util.List<Booking> items = bookingsById.values().stream()
                .sorted(java.util.Comparator.comparing(Booking::getCreatedAt).reversed())
                .skip((long) (page - 1) * pageSize)
                .limit(pageSize)
                .toList();
        return new BookingPage(items, bookingsById.size());
    }

    @Override
    public boolean existsByBookingCode(String bookingCode) {
        return bookingsById.values().stream()
                .anyMatch(booking -> Objects.equals(booking.getBookingCode(), bookingCode));
    }

    @Override
    public boolean existsByShowtimeIdAndSeatId(UUID showtimeId, UUID seatId) {
        return activeBookingBySeat.containsKey(new SeatKey(showtimeId, seatId));
    }

    @Override
    public void cancelById(UUID bookingId, Instant cancelledAt) {
        Booking booking = bookingsById.get(bookingId);
        if (booking != null) {
            removeActiveSeats(booking);
            bookingsById.put(bookingId, new Booking(
                    booking.getBookingId(), booking.getBookingCode(), booking.getUserId(), booking.getShowtimeId(), booking.getSeats(),
                    booking.getTotalAmount(), booking.getCreatedAt(), booking.getPaymentStatus(),
                    BookingStatus.CANCELLED, cancelledAt));
        }
    }

    @Override
    public boolean existsConfirmedByShowtimeId(UUID showtimeId) {
        return bookingsById.values().stream().anyMatch(booking ->
                booking.getShowtimeId().equals(showtimeId)
                        && booking.getStatus() == BookingStatus.CONFIRMED);
    }

    @Override
    public java.util.List<UUID> findBookedSeatIds(UUID showtimeId) {
        return activeBookingBySeat.keySet().stream().filter(key -> key.showtimeId.equals(showtimeId))
                .map(key -> key.seatId).toList();
    }

    int getBookingCount() {
        return bookingsById.size();
    }

    private void removeActiveSeats(Booking booking) {
        for (bookingSeat seat : booking.getSeats()) {
            activeBookingBySeat.remove(new SeatKey(booking.getShowtimeId(), seat.getSeatId()));
        }
    }

    private static final class SeatKey {
        private final UUID showtimeId;
        private final UUID seatId;

        private SeatKey(UUID showtimeId, UUID seatId) {
            this.showtimeId = showtimeId;
            this.seatId = seatId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof SeatKey)) {
                return false;
            }
            SeatKey that = (SeatKey) other;
            return Objects.equals(showtimeId, that.showtimeId)
                    && Objects.equals(seatId, that.seatId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(showtimeId, seatId);
        }
    }
}
