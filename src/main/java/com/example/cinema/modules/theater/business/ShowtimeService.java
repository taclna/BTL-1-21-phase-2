package com.example.cinema.modules.theater.business;

import com.example.cinema.modules.movie.business.MovieModuleApi;
import com.example.cinema.modules.theater.business.ShowtimeModuleApi.SeatKind;
import com.example.cinema.modules.theater.business.ShowtimeModuleApi.SeatSnapshot;
import com.example.cinema.modules.theater.business.ShowtimeModuleApi.ShowtimeSnapshot;
import com.example.cinema.modules.theater.business.ShowtimeModuleApi.ShowtimeState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

public class ShowtimeService implements ShowtimeModuleApi {

    /** Showtimes of one movie on one UTC date. */
    public static final String SCHEDULE_CACHE = "theater-schedules";
    /** Showtime snapshot with its seat layout; never holds booked seats. */
    public static final String SHOWTIME_CACHE = "theater-showtimes";

    private static final String SCHEDULE_KEY_OF_RESULT =
            "{#result.movieId(), #result.startsAt().atOffset(T(java.time.ZoneOffset).UTC).toLocalDate()}";

    private static final Duration CLEANUP_BUFFER = Duration.ofMinutes(15);

    private final ShowtimeRepository showtimeRepository;
    private final AuditoriumRepository auditoriumRepository;
    private final SeatRepository seatRepository;
    private final MovieModuleApi movieModuleApi;
    private final Clock clock;
    private final List<ShowtimeCancellationGuard> cancellationGuards;

    public ShowtimeService(
            ShowtimeRepository showtimeRepository,
            AuditoriumRepository auditoriumRepository,
            SeatRepository seatRepository,
            MovieModuleApi movieModuleApi,
            Clock clock
    ) {
        this(showtimeRepository, auditoriumRepository, seatRepository, movieModuleApi, clock, List.of());
    }

    public ShowtimeService(
            ShowtimeRepository showtimeRepository,
            AuditoriumRepository auditoriumRepository,
            SeatRepository seatRepository,
            MovieModuleApi movieModuleApi,
            Clock clock,
            List<ShowtimeCancellationGuard> cancellationGuards
    ) {
        this.showtimeRepository = showtimeRepository;
        this.auditoriumRepository = auditoriumRepository;
        this.seatRepository = seatRepository;
        this.movieModuleApi = movieModuleApi;
        this.clock = clock;
        this.cancellationGuards = List.copyOf(cancellationGuards);
    }

    @Transactional
    @CacheEvict(cacheNames = SCHEDULE_CACHE, key = SCHEDULE_KEY_OF_RESULT)
    public Showtime create(CreateShowtimeCommand command) {
        MovieModuleApi.MovieSnapshot movie = movieModuleApi
                .findMovieForScheduling(command.movieId())
                .orElseThrow(() -> new MovieNotFoundException(command.movieId()));
        Auditorium auditorium = auditoriumRepository.findByIdForUpdate(command.auditoriumId())
                .orElseThrow(() -> new AuditoriumNotFoundException(command.auditoriumId()));
        if (!auditorium.isActive()) {
            throw new TheaterBusinessException("Auditorium is not active: " + auditorium.id());
        }

        Instant now = clock.instant();
        if (!command.startsAt().isAfter(now)) {
            throw new TheaterBusinessException("Showtime must start in the future");
        }
        Instant endsAt = command.startsAt()
                .plus(Duration.ofMinutes(movie.durationMinutes()))
                .plus(CLEANUP_BUFFER);
        if (showtimeRepository.existsOverlapping(command.auditoriumId(), command.startsAt(), endsAt)) {
            throw new ScheduleOverlapException(command.auditoriumId());
        }

        Showtime showtime = new Showtime(
                UUID.randomUUID(),
                command.movieId(),
                command.auditoriumId(),
                command.startsAt(),
                endsAt,
                command.basePrice(),
                ShowtimeStatus.SCHEDULED,
                now,
                now
        );
        return showtimeRepository.save(showtime);
    }

    public Showtime findById(UUID showtimeId) {
        return showtimeRepository.findById(showtimeId)
                .orElseThrow(() -> new ShowtimeNotFoundException(showtimeId));
    }

    public List<Showtime> findByMovieIdAndStartsAtBetween(UUID movieId, Instant from, Instant to) {
        return showtimeRepository.findByMovieIdAndStartsAtBetween(movieId, from, to);
    }

    /**
     * Cached reads use {@code sync = true} and writes evict exact keys on purpose: with the in-process Caffeine
     * cache an eviction then waits for a load already in progress, so a read that started before the commit
     * cannot leave its old value behind. TheaterCacheIntegrationTest guards this behaviour.
     */
    @Cacheable(cacheNames = SCHEDULE_CACHE, key = "{#movieId, #date}", sync = true)
    public List<Showtime> findByMovieIdAndDate(UUID movieId, LocalDate date) {
        Instant from = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return List.copyOf(showtimeRepository.findByMovieIdAndStartsAtBetween(movieId, from, to));
    }

    /** Returns the cancelled showtime so its schedule cache entry can be evicted by exact key. */
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = SHOWTIME_CACHE, key = "#showtimeId"),
            @CacheEvict(cacheNames = SCHEDULE_CACHE, key = SCHEDULE_KEY_OF_RESULT)
    })
    public Showtime cancel(UUID showtimeId) {
        Showtime showtime = showtimeRepository.findByIdForUpdate(showtimeId)
                .orElseThrow(() -> new ShowtimeNotFoundException(showtimeId));
        Instant now = clock.instant();
        if (showtime.status() != ShowtimeStatus.SCHEDULED || !showtime.startsAt().isAfter(now)) {
            throw new TheaterBusinessException("Showtime cannot be cancelled: " + showtimeId);
        }
        cancellationGuards.forEach(guard -> guard.checkCancellationAllowed(showtimeId));
        Showtime cancelled = new Showtime(
                showtime.id(),
                showtime.movieId(),
                showtime.auditoriumId(),
                showtime.startsAt(),
                showtime.endsAt(),
                showtime.basePrice(),
                ShowtimeStatus.CANCELLED,
                showtime.createdAt(),
                now
        );
        showtimeRepository.save(cancelled);
        return cancelled;
    }

    @Override
    public ShowtimeSnapshot getShowtimeSnapshot(UUID showtimeId) {
        if (showtimeId == null) {
            return null;
        }
        return showtimeRepository.findById(showtimeId).map(this::toSnapshot).orElse(null);
    }

    /**
     * Overridden because the inherited default calls {@link #getShowtimeSnapshot} on {@code this} and would
     * bypass the cache proxy. The lock methods below must always read the database and are never cached.
     */
    @Override
    @Cacheable(cacheNames = SHOWTIME_CACHE, key = "#showtimeId", condition = "#showtimeId != null", sync = true)
    public Optional<ShowtimeSnapshot> findShowtime(UUID showtimeId) {
        return Optional.ofNullable(getShowtimeSnapshot(showtimeId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ShowtimeSnapshot lockShowtimeForBooking(UUID showtimeId) {
        return showtimeRepository.findByIdForUpdate(showtimeId).map(this::toSnapshot).orElse(null);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ShowtimeSnapshot lockShowtimeShareForBooking(UUID showtimeId) {
        return showtimeRepository.findByIdForShare(showtimeId).map(this::toSnapshot).orElse(null);
    }

    @Override
    public List<SeatSnapshot> getSeatSnapshots(List<UUID> seatIds) {
        if (seatIds == null || seatIds.isEmpty()) {
            return List.of();
        }
        return seatRepository.findByIds(seatIds).stream()
                .map(this::toSnapshot)
                .toList();
    }

    private ShowtimeSnapshot toSnapshot(Showtime showtime) {
        return new ShowtimeSnapshot(
                    showtime.id(),
                    showtime.auditoriumId(),
                    showtime.startsAt(),
                    showtime.basePrice(),
                    switch (showtime.status()) {
                        case SCHEDULED -> ShowtimeState.SCHEDULED;
                        case CANCELLED -> ShowtimeState.CANCELLED;
                        case FINISHED -> ShowtimeState.FINISHED;
                    },
                    seatRepository.findByAuditoriumId(showtime.auditoriumId()).stream()
                            .map(this::toSnapshot).toList());
    }

    private SeatSnapshot toSnapshot(Seat seat) {
        return new SeatSnapshot(seat.id(), seat.auditoriumId(), seat.rowLabel(), seat.seatNumber(),
                seat.type() == SeatType.VIP ? SeatKind.VIP : SeatKind.STANDARD);
    }
}
