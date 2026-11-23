package com.example.cinema.modules.booking.business;

import com.example.cinema.modules.theater.business.ShowtimeModuleApi;
import com.example.cinema.modules.theater.business.ShowtimeNotFoundException;
import com.example.cinema.modules.theater.business.ShowtimeModuleApi.SeatKind;
import com.example.cinema.modules.theater.business.ShowtimeModuleApi.SeatSnapshot;
import com.example.cinema.modules.theater.business.ShowtimeModuleApi.ShowtimeSnapshot;
import com.example.cinema.modules.theater.business.ShowtimeModuleApi.ShowtimeState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "cinema.booking.enabled", havingValue = "true", matchIfMissing = true)
public class BookingService {

    private static final BigDecimal VIP_MULTIPLIER = new BigDecimal("1.20");

    private final BookingRepository bookingRepository;
    private final BookingIdempotencyRepository idempotencyRepository;
    private final ShowtimeModuleApi showtimeModuleApi;
    private final Clock clock;
    private final Counter bookingCreatedCounter;
    private final Counter bookingSeatConflictCounter;
    private final Counter idempotencyCreatedCounter;
    private final Counter idempotencyReplayedCounter;
    private final Counter idempotencyReusedCounter;

    @Autowired
    public BookingService(
            BookingRepository bookingRepository,
            BookingIdempotencyRepository idempotencyRepository,
            ShowtimeModuleApi showtimeModuleApi,
            MeterRegistry meterRegistry
    ) {
        this(bookingRepository, idempotencyRepository, showtimeModuleApi, Clock.systemUTC(), meterRegistry);
    }

    public BookingService(
            BookingRepository bookingRepository,
            BookingIdempotencyRepository idempotencyRepository,
            ShowtimeModuleApi showtimeModuleApi,
            Clock clock
    ) {
        this(bookingRepository, idempotencyRepository, showtimeModuleApi, clock, new SimpleMeterRegistry());
    }

    public BookingService(
            BookingRepository bookingRepository,
            BookingIdempotencyRepository idempotencyRepository,
            ShowtimeModuleApi showtimeModuleApi,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.bookingRepository = bookingRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.showtimeModuleApi = showtimeModuleApi;
        this.clock = clock;
        this.bookingCreatedCounter = Counter.builder("booking.creation").register(meterRegistry);
        this.bookingSeatConflictCounter = Counter.builder("booking.seat_conflict").register(meterRegistry);
        this.idempotencyCreatedCounter = Counter.builder("booking.idempotency.created").register(meterRegistry);
        this.idempotencyReplayedCounter = Counter.builder("booking.idempotency.replayed").register(meterRegistry);
        this.idempotencyReusedCounter = Counter.builder("booking.idempotency.reused").register(meterRegistry);
    }

    @Transactional
    public Booking createBooking(UUID userId, UUID showtimeId, List<UUID> seatIds) {
        return createBooking(userId, UUID.randomUUID().toString(), showtimeId, seatIds).booking();
    }

    @Transactional
    public BookingCreationResult createBooking(
            UUID userId,
            String rawIdempotencyKey,
            UUID showtimeId,
            List<UUID> seatIds
    ) {
        UUID idempotencyKey = parseIdempotencyKey(rawIdempotencyKey);
        validateBookingRequest(userId, showtimeId, seatIds);
        String requestHash = hashRequest(showtimeId, seatIds);

        if (!idempotencyRepository.claim(userId, idempotencyKey, requestHash)) {
            BookingIdempotency existing = idempotencyRepository.find(userId, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Claimed idempotency key disappeared"));
            if (!requestHash.equals(existing.requestHash())) {
                idempotencyReusedCounter.increment();
                throw new IdempotencyKeyReusedException();
            }
            if (existing.bookingId() == null) {
                throw new IllegalStateException("Committed idempotency record has no booking");
            }
            Booking booking = bookingRepository.findByIdAndUserId(existing.bookingId(), userId)
                    .orElseThrow(() -> new IllegalStateException("Idempotency booking disappeared"));
            idempotencyReplayedCounter.increment();
            return BookingCreationResult.replayed(booking);
        }

        try {
            ShowtimeSnapshot showtime = showtimeModuleApi.lockShowtimeForBooking(showtimeId);
            List<SeatSnapshot> seatSnapshots = showtimeModuleApi.getSeatSnapshots(seatIds);

            validateShowtime(showtime, showtimeId);
            validateSeats(showtime, seatSnapshots, seatIds);
            validateSeatAvailable(seatIds, showtimeId);

            List<bookingSeat> bookingSeats = createBookingSeats(showtime, seatSnapshots);
            BigDecimal totalAmount = getTotalAmount(bookingSeats);

            String bookingCode = generateAvailableBookingCode();
            Booking booking = new Booking(UUID.randomUUID(), bookingCode, userId, showtimeId, bookingSeats, totalAmount);
            Booking savedBooking = bookingRepository.save(booking);
            idempotencyRepository.attachBooking(
                    userId,
                    idempotencyKey,
                    requestHash,
                    savedBooking.getBookingId()
            );
            bookingCreatedCounter.increment();
            idempotencyCreatedCounter.increment();
            return BookingCreationResult.created(savedBooking);
        } catch (SeatAlreadyBookedException exception) {
            bookingSeatConflictCounter.increment();
            throw exception;
        }
    }

    private UUID parseIdempotencyKey(String rawIdempotencyKey) {
        if (rawIdempotencyKey == null || rawIdempotencyKey.isBlank()) {
            throw new InvalidIdempotencyKeyException();
        }
        try {
            UUID parsed = UUID.fromString(rawIdempotencyKey);
            if (!parsed.toString().equalsIgnoreCase(rawIdempotencyKey)) {
                throw new InvalidIdempotencyKeyException();
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw new InvalidIdempotencyKeyException();
        }
    }

    private String hashRequest(UUID showtimeId, List<UUID> seatIds) {
        String sortedSeats = seatIds.stream()
                .sorted(Comparator.comparing(UUID::toString))
                .map(UUID::toString)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        String canonicalRequest = "v1\nshowtimeId=" + showtimeId + "\nseatIds=" + sortedSeats;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String generateAvailableBookingCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String code = BookingCodeGenerator.generate();
            if (!bookingRepository.existsByBookingCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Could not generate a unique booking code");
    }

    private void validateSeatAvailable(List<UUID> seatIds, UUID showtimeId) {
        for (UUID seatId : seatIds) {
            if (bookingRepository.existsByShowtimeIdAndSeatId(showtimeId, seatId)) {
                throw new SeatAlreadyBookedException();
            }
        }
    }

    @Transactional
    public void cancelBooking(UUID userId, UUID bookingId) {
        if (userId == null || bookingId == null) {
            throw new IllegalArgumentException("userId and bookingId must not be null");
        }

        Booking booking = bookingRepository.findByIdAndUserIdForUpdate(bookingId, userId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new BookingCannotBeCancelledException(bookingId);
        }

        ShowtimeSnapshot showtime = showtimeModuleApi.findShowtime(booking.getShowtimeId())
                .orElseThrow(() -> new BookingCannotBeCancelledException(bookingId));
        Instant now = clock.instant();
        Instant cancellationDeadline = showtime.startsAt().minus(Duration.ofMinutes(30));
        if (now.isAfter(cancellationDeadline)) {
            throw new BookingCannotBeCancelledException(bookingId);
        }

        bookingRepository.cancelById(bookingId, now);
    }

    public List<Booking> getBookingsByUserId(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        return bookingRepository.findAllByUserId(userId);
    }

    public BookingPage getBookingsByUserId(UUID userId, int page, int pageSize) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        validatePage(page, pageSize);
        return bookingRepository.findAllByUserId(userId, page, pageSize);
    }

    public Booking getBookingByUserId(UUID userId, UUID bookingId) {
        if (userId == null || bookingId == null) {
            throw new IllegalArgumentException("userId and bookingId must not be null");
        }
        return bookingRepository.findByIdAndUserId(bookingId, userId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
    }

    public BookingPage getAllBookings(int page, int pageSize) {
        validatePage(page, pageSize);
        return bookingRepository.findAll(page, pageSize);
    }

    public Booking getBookingById(UUID bookingId) {
        if (bookingId == null) {
            throw new IllegalArgumentException("bookingId must not be null");
        }
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
    }

    @Transactional
    public void cancelBookingByAdmin(UUID bookingId) {
        if (bookingId == null) {
            throw new IllegalArgumentException("bookingId must not be null");
        }
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new BookingCannotBeCancelledException(bookingId);
        }
        ShowtimeSnapshot showtime = showtimeModuleApi.findShowtime(booking.getShowtimeId())
                .orElseThrow(() -> new BookingCannotBeCancelledException(bookingId));
        Instant now = clock.instant();
        if (now.isAfter(showtime.startsAt().minus(Duration.ofMinutes(30)))) {
            throw new BookingCannotBeCancelledException(bookingId);
        }
        bookingRepository.cancelById(bookingId, now);
    }

    @Transactional(readOnly = true)
    public List<SeatAvailability> getSeatAvailability(UUID showtimeId) {
        ShowtimeSnapshot showtime = showtimeModuleApi.findShowtime(showtimeId)
                .orElseThrow(() -> new ShowtimeNotFoundException(showtimeId));
        var bookedSeats = new HashSet<>(bookingRepository.findBookedSeatIds(showtimeId));
        return showtime.seats().stream()
                .map(seat -> new SeatAvailability(seat, bookedSeats.contains(seat.seatId())))
                .toList();
    }

    private BigDecimal getTotalAmount(List<bookingSeat> seats) {
        BigDecimal total = BigDecimal.ZERO;
        for (bookingSeat seat : seats) {
            total = total.add(seat.getPrice());
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private List<bookingSeat> createBookingSeats(
            ShowtimeSnapshot showtime,
            List<SeatSnapshot> seatSnapshots
    ) {
        List<bookingSeat> bookingSeats = new ArrayList<>();
        for (SeatSnapshot seatSnapshot : seatSnapshots) {
            BigDecimal price = calculateSeatPrice(showtime.basePrice(), seatSnapshot.type());
            bookingSeats.add(new bookingSeat(seatSnapshot.seatId(), price));
        }
        return bookingSeats;
    }

    private void validateBookingRequest(
            UUID userId,
            UUID showtimeId,
            List<UUID> seatIds
    ) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        if (showtimeId == null) {
            throw new IllegalArgumentException("showtimeId must not be null");
        }
        if (seatIds == null || seatIds.isEmpty() || seatIds.size() > 8) {
            throw new IllegalArgumentException("Booking must contain from 1 to 8 seats");
        }
        if (seatIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("seatIds must not contain null");
        }
        if (new HashSet<>(seatIds).size() != seatIds.size()) {
            throw new IllegalArgumentException("seatIds must not contain duplicate IDs");
        }
    }

    private void validateShowtime(ShowtimeSnapshot showtime, UUID showtimeId) {
        if (showtime == null || !showtimeId.equals(showtime.showtimeId())) {
            throw new IllegalArgumentException("Showtime was not found");
        }
        if (showtime.status() != ShowtimeState.SCHEDULED) {
            throw new IllegalArgumentException("Showtime is not available for booking");
        }
        if (showtime.startsAt() == null || !showtime.startsAt().isAfter(clock.instant())) {
            throw new IllegalArgumentException("Showtime has already started");
        }
        if (showtime.auditoriumId() == null) {
            throw new IllegalArgumentException("Showtime auditorium is missing");
        }
        if (showtime.basePrice() == null || showtime.basePrice().signum() <= 0) {
            throw new IllegalArgumentException("Showtime base price must be positive");
        }
    }

    private void validateSeats(
            ShowtimeSnapshot showtime,
            List<SeatSnapshot> seatSnapshots,
            List<UUID> requestedSeatIds
    ) {
        if (seatSnapshots == null || seatSnapshots.size() != requestedSeatIds.size()) {
            throw new IllegalArgumentException("Theater did not return every requested seat");
        }

        HashSet<UUID> returnedSeatIds = new HashSet<>();
        HashSet<UUID> showtimeSeatIds = new HashSet<>();
        for (SeatSnapshot seat : showtime.seats()) {
            showtimeSeatIds.add(seat.seatId());
        }
        for (SeatSnapshot seatSnapshot : seatSnapshots) {
            if (seatSnapshot == null
                    || seatSnapshot.seatId() == null
                    || !requestedSeatIds.contains(seatSnapshot.seatId())
                    || !returnedSeatIds.add(seatSnapshot.seatId())) {
                throw new IllegalArgumentException("Theater returned an invalid seat list");
            }
            if (!showtime.auditoriumId().equals(seatSnapshot.auditoriumId())
                    || !showtimeSeatIds.contains(seatSnapshot.seatId())) {
                throw new IllegalArgumentException("Seat does not belong to showtime auditorium");
            }
            if (seatSnapshot.type() == null) {
                throw new IllegalArgumentException("Seat type is missing");
            }
        }
    }

    private void validatePage(int page, int pageSize) {
        if (page < 1) {
            throw new IllegalArgumentException("Page must be at least 1");
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("Page size must be between 1 and 100");
        }
    }

    private BigDecimal calculateSeatPrice(BigDecimal basePrice, SeatKind seatType) {
        BigDecimal price = seatType == SeatKind.VIP
                ? basePrice.multiply(VIP_MULTIPLIER)
                : basePrice;
        return price.setScale(2, RoundingMode.HALF_UP);
    }
}
