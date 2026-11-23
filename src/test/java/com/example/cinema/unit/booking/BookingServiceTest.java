package com.example.cinema.unit.booking;

import com.example.cinema.modules.booking.business.Booking;
import com.example.cinema.modules.booking.business.BookingCannotBeCancelledException;
import com.example.cinema.modules.booking.business.IdempotencyKeyReusedException;
import com.example.cinema.modules.booking.business.InvalidIdempotencyKeyException;
import com.example.cinema.modules.booking.business.BookingNotFoundException;
import com.example.cinema.modules.booking.business.BookingService;
import com.example.cinema.modules.booking.business.BookingStatus;
import com.example.cinema.modules.booking.business.SeatAlreadyBookedException;
import com.example.cinema.modules.theater.business.ShowtimeModuleApi.SeatKind;
import com.example.cinema.modules.theater.business.ShowtimeModuleApi.SeatSnapshot;
import com.example.cinema.modules.theater.business.ShowtimeModuleApi.ShowtimeSnapshot;
import com.example.cinema.modules.theater.business.ShowtimeModuleApi.ShowtimeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BookingServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-30T10:00:00Z");

    private final UUID userId = UUID.randomUUID();
    private final UUID showtimeId = UUID.randomUUID();
    private final UUID auditoriumId = UUID.randomUUID();
    private final UUID standardSeatId = UUID.randomUUID();
    private final UUID vipSeatId = UUID.randomUUID();

    private FakeBookingRepository bookingRepository;
    private FakeBookingIdempotencyRepository idempotencyRepository;
    private FakeShowtimeModuleApi theaterApi;
    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        bookingRepository = new FakeBookingRepository();
        idempotencyRepository = new FakeBookingIdempotencyRepository();
        theaterApi = new FakeShowtimeModuleApi();
        theaterApi.setShowtimeSnapshot(new ShowtimeSnapshot(
                showtimeId,
                auditoriumId,
                NOW.plusSeconds(3_600),
                new BigDecimal("100000.00"),
                ShowtimeState.SCHEDULED,
                List.of()
        ));
        theaterApi.setSeats(List.of(
                new SeatSnapshot(standardSeatId, auditoriumId, "A", 1, SeatKind.STANDARD),
                new SeatSnapshot(vipSeatId, auditoriumId, "A", 2, SeatKind.VIP)
        ));
        bookingService = new BookingService(
                bookingRepository,
                idempotencyRepository,
                theaterApi,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void createsBookingWithCorrectSeatPricesAndTotal() {
        Booking booking = bookingService.createBooking(
                userId,
                showtimeId,
                List.of(standardSeatId, vipSeatId)
        );

        assertNotNull(booking.getBookingId());
        assertEquals(userId, booking.getUserId());
        assertEquals(showtimeId, booking.getShowtimeId());
        assertEquals(2, booking.getSeats().size());
        assertEquals(new BigDecimal("100000.00"), booking.getSeats().get(0).getPrice());
        assertEquals(new BigDecimal("120000.00"), booking.getSeats().get(1).getPrice());
        assertEquals(new BigDecimal("220000.00"), booking.getTotalAmount());
        assertEquals(1, bookingRepository.getBookingCount());
    }

    @Test
    void replaysTheSameBookingForTheSameKeyAndPayload() {
        String key = UUID.randomUUID().toString();

        var created = bookingService.createBooking(
                userId, key, showtimeId, List.of(standardSeatId, vipSeatId));
        var replayed = bookingService.createBooking(
                userId, key, showtimeId, List.of(vipSeatId, standardSeatId));

        assertEquals(false, created.replayed());
        assertEquals(true, replayed.replayed());
        assertEquals(created.booking().getBookingId(), replayed.booking().getBookingId());
        assertEquals(1, bookingRepository.getBookingCount());
    }

    @Test
    void rejectsReusingAKeyWithADifferentPayload() {
        String key = UUID.randomUUID().toString();
        bookingService.createBooking(userId, key, showtimeId, List.of(standardSeatId));

        assertThrows(IdempotencyKeyReusedException.class,
                () -> bookingService.createBooking(userId, key, showtimeId, List.of(vipSeatId)));
        assertEquals(1, bookingRepository.getBookingCount());
    }

    @Test
    void scopesIdempotencyKeysByUser() {
        String key = UUID.randomUUID().toString();
        var first = bookingService.createBooking(
                userId, key, showtimeId, List.of(standardSeatId));
        var second = bookingService.createBooking(
                UUID.randomUUID(), key, showtimeId, List.of(vipSeatId));

        assertEquals(false, first.replayed());
        assertEquals(false, second.replayed());
        assertEquals(2, bookingRepository.getBookingCount());
    }

    @Test
    void rejectsMissingOrMalformedIdempotencyKeys() {
        assertThrows(InvalidIdempotencyKeyException.class,
                () -> bookingService.createBooking(userId, null, showtimeId, List.of(standardSeatId)));
        assertThrows(InvalidIdempotencyKeyException.class,
                () -> bookingService.createBooking(userId, "not-a-uuid", showtimeId, List.of(standardSeatId)));
        assertEquals(0, bookingRepository.getBookingCount());
    }

    @Test
    void replayReturnsTheCurrentCancelledBooking() {
        String key = UUID.randomUUID().toString();
        var created = bookingService.createBooking(
                userId, key, showtimeId, List.of(standardSeatId));
        bookingService.cancelBooking(userId, created.booking().getBookingId());

        var replayed = bookingService.createBooking(
                userId, key, showtimeId, List.of(standardSeatId));

        assertEquals(true, replayed.replayed());
        assertEquals(BookingStatus.CANCELLED, replayed.booking().getStatus());
        assertEquals(1, bookingRepository.getBookingCount());
    }

    @Test
    void rejectsEmptySeatList() {
        assertThrows(IllegalArgumentException.class,
                () -> bookingService.createBooking(userId, showtimeId, List.of()));
    }

    @Test
    void rejectsMoreThanEightSeats() {
        List<UUID> nineSeatIds = List.of(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()
        );

        assertThrows(IllegalArgumentException.class,
                () -> bookingService.createBooking(userId, showtimeId, nineSeatIds));
    }

    @Test
    void rejectsDuplicateSeatIds() {
        assertThrows(IllegalArgumentException.class,
                () -> bookingService.createBooking(
                        userId,
                        showtimeId,
                        List.of(standardSeatId, standardSeatId)
                ));
    }

    @Test
    void rejectsCancelledShowtime() {
        theaterApi.setShowtimeSnapshot(new ShowtimeSnapshot(
                showtimeId,
                auditoriumId,
                NOW.plusSeconds(3_600),
                new BigDecimal("100000.00"),
                ShowtimeState.CANCELLED,
                List.of()
        ));

        assertThrows(IllegalArgumentException.class,
                () -> bookingService.createBooking(userId, showtimeId, List.of(standardSeatId)));
    }

    @Test
    void rejectsShowtimeThatHasStarted() {
        theaterApi.setShowtimeSnapshot(new ShowtimeSnapshot(
                showtimeId,
                auditoriumId,
                NOW,
                new BigDecimal("100000.00"),
                ShowtimeState.SCHEDULED,
                List.of()
        ));

        assertThrows(IllegalArgumentException.class,
                () -> bookingService.createBooking(userId, showtimeId, List.of(standardSeatId)));
    }

    @Test
    void rejectsSeatThatDoesNotBelongToShowtime() {
        theaterApi.setSeats(List.of(
                new SeatSnapshot(UUID.randomUUID(), auditoriumId, "A", 1, SeatKind.STANDARD)
        ));

        assertThrows(IllegalArgumentException.class,
                () -> bookingService.createBooking(userId, showtimeId, List.of(standardSeatId)));
    }

    @Test
    void rejectsSeatThatTheaterDoesNotReturn() {
        UUID unknownSeatId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                () -> bookingService.createBooking(userId, showtimeId, List.of(unknownSeatId)));
    }

    @Test
    void rejectsSeatAlreadyBookedForSameShowtime() {
        bookingService.createBooking(userId, showtimeId, List.of(standardSeatId));

        assertThrows(SeatAlreadyBookedException.class,
                () -> bookingService.createBooking(UUID.randomUUID(), showtimeId, List.of(standardSeatId)));
    }

    @Test
    void canBookSeatAgainAfterCancellingPreviousBooking() {
        Booking firstBooking = bookingService.createBooking(
                userId,
                showtimeId,
                List.of(standardSeatId)
        );

        bookingService.cancelBooking(userId, firstBooking.getBookingId());

        Booking newBooking = assertDoesNotThrow(() -> bookingService.createBooking(
                UUID.randomUUID(),
                showtimeId,
                List.of(standardSeatId)
        ));

        assertNotNull(newBooking.getBookingId());
        assertEquals(2, bookingRepository.getBookingCount());
    }

    @Test
    void cancellationRequiresOwnershipAndReleasesSeats() {
        Booking booking = bookingService.createBooking(userId, showtimeId, List.of(standardSeatId));

        assertThrows(BookingNotFoundException.class,
                () -> bookingService.cancelBooking(UUID.randomUUID(), booking.getBookingId()));
        assertEquals(1, bookingRepository.getBookingCount());

        bookingService.cancelBooking(userId, booking.getBookingId());
        Booking cancelled = bookingService.getBookingByUserId(userId, booking.getBookingId());
        assertEquals(1, bookingRepository.getBookingCount());
        assertEquals(BookingStatus.CANCELLED, cancelled.getStatus());
        assertEquals(NOW, cancelled.getCancelledAt());
        assertDoesNotThrow(() -> bookingService.createBooking(UUID.randomUUID(), showtimeId, List.of(standardSeatId)));
    }

    @Test
    void allowsCancellationExactlyThirtyMinutesBeforeShowtime() {
        setShowtimeStart(NOW.plusSeconds(30 * 60));
        Booking booking = bookingService.createBooking(userId, showtimeId, List.of(standardSeatId));

        assertDoesNotThrow(() -> bookingService.cancelBooking(userId, booking.getBookingId()));
        assertEquals(BookingStatus.CANCELLED,
                bookingRepository.findById(booking.getBookingId()).orElseThrow().getStatus());
    }

    @Test
    void rejectsCancellationLessThanThirtyMinutesBeforeShowtime() {
        setShowtimeStart(NOW.plusSeconds(30 * 60 - 1));
        Booking booking = bookingService.createBooking(userId, showtimeId, List.of(standardSeatId));

        assertThrows(BookingCannotBeCancelledException.class,
                () -> bookingService.cancelBooking(userId, booking.getBookingId()));
        assertEquals(BookingStatus.CONFIRMED,
                bookingRepository.findById(booking.getBookingId()).orElseThrow().getStatus());
        assertEquals(true, bookingRepository.existsByShowtimeIdAndSeatId(showtimeId, standardSeatId));
    }

    @Test
    void rejectsCancellingAnAlreadyCancelledBooking() {
        Booking booking = bookingService.createBooking(userId, showtimeId, List.of(standardSeatId));
        bookingService.cancelBooking(userId, booking.getBookingId());

        assertThrows(BookingCannotBeCancelledException.class,
                () -> bookingService.cancelBooking(userId, booking.getBookingId()));
    }

    @Test
    void rejectsSeatFromAnotherAuditoriumEvenWhenItAppearsInShowtimeSeatList() {
        theaterApi.setSeats(List.of(new SeatSnapshot(standardSeatId, UUID.randomUUID(), "A", 1, SeatKind.STANDARD)));
        assertThrows(IllegalArgumentException.class,
                () -> bookingService.createBooking(userId, showtimeId, List.of(standardSeatId)));
        assertEquals(0, bookingRepository.getBookingCount());
    }

    @Test
    void bookingGuardRejectsConfirmedBookingsAndAllowsShowtimesWithoutBookings() {
        var guard = new com.example.cinema.modules.booking.business.BookingShowtimeCancellationGuard(bookingRepository);
        assertDoesNotThrow(() -> guard.checkCancellationAllowed(showtimeId));
        Booking booking = bookingService.createBooking(userId, showtimeId, List.of(standardSeatId));
        assertThrows(com.example.cinema.modules.theater.business.ShowtimeHasBookingsException.class,
                () -> guard.checkCancellationAllowed(showtimeId));
        bookingService.cancelBooking(userId, booking.getBookingId());
        assertDoesNotThrow(() -> guard.checkCancellationAllowed(showtimeId));
    }

    @Test
    void seatAvailabilityCombinesTheaterLayoutWithBookedSeats() {
        bookingService.createBooking(userId, showtimeId, List.of(standardSeatId));
        var seats = bookingService.getSeatAvailability(showtimeId);
        assertEquals(2, seats.size());
        assertEquals(standardSeatId, seats.get(0).seat().seatId());
        assertEquals(true, seats.get(0).booked());
        assertEquals(false, seats.get(1).booked());
        assertThrows(com.example.cinema.modules.theater.business.ShowtimeNotFoundException.class,
                () -> bookingService.getSeatAvailability(UUID.randomUUID()));
    }

    private void setShowtimeStart(Instant startsAt) {
        theaterApi.setShowtimeSnapshot(new ShowtimeSnapshot(
                showtimeId,
                auditoriumId,
                startsAt,
                new BigDecimal("100000.00"),
                ShowtimeState.SCHEDULED,
                List.of()
        ));
    }
}
