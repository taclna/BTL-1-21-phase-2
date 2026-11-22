package com.example.cinema.modules.booking.data;

import com.example.cinema.modules.booking.business.Booking;
import com.example.cinema.modules.booking.business.BookingRepository;
import com.example.cinema.modules.booking.business.BookingStatus;
import com.example.cinema.modules.booking.business.SeatAlreadyBookedException;
import com.example.cinema.modules.booking.business.bookingSeat;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@ConditionalOnProperty(name = "cinema.booking.enabled", havingValue = "true", matchIfMissing = true)
class BookingRepositoryAdapter implements BookingRepository {

    private final BookingJpaRepository bookingJpaRepository;
    private final BookingSeatJpaRepository bookingSeatJpaRepository;

    BookingRepositoryAdapter(
            BookingJpaRepository bookingJpaRepository,
            BookingSeatJpaRepository bookingSeatJpaRepository
    ) {
        this.bookingJpaRepository = bookingJpaRepository;
        this.bookingSeatJpaRepository = bookingSeatJpaRepository;
    }

    @Override
    public Booking save(Booking booking) {
        try {
            bookingJpaRepository.save(new BookingEntity(
                    booking.getBookingId(), booking.getBookingCode(), booking.getUserId(), booking.getShowtimeId(),
                    booking.getTotalAmount(), booking.getCreatedAt(), booking.getPaymentStatus(),
                    booking.getStatus(), booking.getCancelledAt()));

            List<BookingSeatEntity> seatEntities = booking.getSeats().stream()
                    .map(seat -> new BookingSeatEntity(
                            UUID.randomUUID(), booking.getBookingId(), booking.getShowtimeId(),
                            seat.getSeatId(), seat.getPrice(), null))
                    .toList();
            bookingSeatJpaRepository.saveAllAndFlush(seatEntities);
            return booking;
        } catch (DataIntegrityViolationException exception) {
            if (isConstraint(exception, "uq_active_showtime_seat")) {
                throw new SeatAlreadyBookedException();
            }
            throw exception;
        }
    }

    @Override
    public Optional<Booking> findById(UUID bookingId) {
        return bookingJpaRepository.findById(bookingId).map(this::toBooking);
    }

    @Override
    public Optional<Booking> findByIdForUpdate(UUID bookingId) {
        return bookingJpaRepository.findForUpdateById(bookingId).map(this::toBooking);
    }

    @Override
    public Optional<Booking> findByIdAndUserId(UUID bookingId, UUID userId) {
        return bookingJpaRepository.findByIdAndUserId(bookingId, userId).map(this::toBooking);
    }

    @Override
    public Optional<Booking> findByIdAndUserIdForUpdate(UUID bookingId, UUID userId) {
        return bookingJpaRepository.findForUpdateByIdAndUserId(bookingId, userId).map(this::toBooking);
    }

    @Override
    public List<Booking> findAllByUserId(UUID userId) {
        return bookingJpaRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toBooking)
                .toList();
    }

    @Override
    public com.example.cinema.modules.booking.business.BookingPage findAllByUserId(
            UUID userId, int page, int pageSize
    ) {
        PageRequest request = PageRequest.of(page - 1, pageSize,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<BookingEntity> result =
                bookingJpaRepository.findAllByUserId(userId, request);
        return new com.example.cinema.modules.booking.business.BookingPage(
                result.getContent().stream().map(this::toBooking).toList(),
                result.getTotalElements());
    }

    @Override
    public com.example.cinema.modules.booking.business.BookingPage findAll(int page, int pageSize) {
        PageRequest request = PageRequest.of(page - 1, pageSize,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<BookingEntity> result = bookingJpaRepository.findAll(request);
        return new com.example.cinema.modules.booking.business.BookingPage(
                result.getContent().stream().map(this::toBooking).toList(),
                result.getTotalElements());
    }

    @Override
    public boolean existsByBookingCode(String bookingCode) {
        return bookingJpaRepository.existsByBookingCode(bookingCode);
    }

    @Override
    public boolean existsByShowtimeIdAndSeatId(UUID showtimeId, UUID seatId) {
        return bookingSeatJpaRepository.existsByShowtimeIdAndSeatIdAndReleasedAtIsNull(showtimeId, seatId);
    }

    @Override
    @Transactional
    public void cancelById(UUID bookingId, Instant cancelledAt) {
        BookingEntity booking = bookingJpaRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalStateException("Locked booking disappeared: " + bookingId));
        booking.cancel(cancelledAt);
        bookingSeatJpaRepository.findAllByBookingId(bookingId)
                .forEach(seat -> seat.release(cancelledAt));
    }

    @Override
    public boolean existsConfirmedByShowtimeId(UUID showtimeId) {
        return bookingJpaRepository.existsByShowtimeIdAndStatus(showtimeId, BookingStatus.CONFIRMED);
    }

    @Override
    public List<UUID> findBookedSeatIds(UUID showtimeId) {
        return bookingSeatJpaRepository.findBookedSeatIds(showtimeId);
    }

    private Booking toBooking(BookingEntity entity) {
        List<bookingSeat> seats = bookingSeatJpaRepository.findAllByBookingId(entity.getId()).stream()
                .map(seat -> new bookingSeat(seat.getSeatId(), seat.getPrice()))
                .toList();
        return new Booking(entity.getId(), entity.getBookingCode(), entity.getUserId(), entity.getShowtimeId(), seats,
                entity.getTotalAmount(), entity.getCreatedAt(), entity.getPaymentStatus(),
                entity.getStatus(), entity.getCancelledAt());
    }

    private static boolean isConstraint(Throwable failure, String constraintName) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && constraintName.equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
