package com.example.cinema.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.cinema.modules.booking.business.BookingService;
import com.example.cinema.modules.theater.business.Auditorium;
import com.example.cinema.modules.theater.business.AuditoriumService;
import com.example.cinema.modules.theater.business.CreateAuditoriumCommand;
import com.example.cinema.modules.theater.business.CreateShowtimeCommand;
import com.example.cinema.modules.theater.business.SeatType;
import com.example.cinema.modules.theater.business.Showtime;
import com.example.cinema.modules.theater.business.ShowtimeHasBookingsException;
import com.example.cinema.modules.theater.business.ShowtimeModuleApi;
import com.example.cinema.modules.theater.business.ShowtimeModuleApi.ShowtimeState;
import com.example.cinema.modules.theater.business.ShowtimeRepository;
import com.example.cinema.modules.theater.business.ShowtimeService;
import com.example.cinema.modules.theater.business.ShowtimeStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 2, experiment E2 (Theater part): on one instance the cache must serve repeated reads and must
 * never show data older than the last committed Admin write.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TheaterCacheIntegrationTest {

    private static final long LIMIT_SECONDS = 10;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired MockMvc mvc;
    @Autowired AuditoriumService auditoriums;
    @Autowired ShowtimeService showtimes;
    @Autowired ShowtimeModuleApi showtimeModuleApi;
    @Autowired BookingService bookings;
    @Autowired CacheManager cacheManager;
    @Autowired MeterRegistry meters;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean ShowtimeRepository repository;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private UUID movie;
    private UUID otherMovie;
    private UUID userId;
    private Auditorium room;
    private LocalDate date;

    @BeforeEach
    void seed() {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
        movie = insertMovie();
        otherMovie = insertMovie();
        userId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id,username,password_hash,role,created_at,updated_at) VALUES (?,?,'test','CUSTOMER',now(),now())",
                userId, "cache-" + userId);
        room = auditoriums.create(new CreateAuditoriumCommand("Cache " + UUID.randomUUID(),
                List.of(new CreateAuditoriumCommand.SeatDefinition("A", 1, 2, SeatType.STANDARD))));
        date = LocalDate.now(ZoneOffset.UTC).plusDays(3);
    }

    @AfterEach
    void stopThreads() {
        executor.shutdownNow();
    }

    @Test
    void repeatedScheduleReadsHitTheDatabaseOnce() throws Exception {
        create(movie, 8);
        clearInvocations(repository);

        expectScheduleSize(movie, 1);
        expectScheduleSize(movie, 1);

        verify(repository, times(1)).findByMovieIdAndStartsAtBetween(eq(movie), any(), any());
    }

    @Test
    void repeatedSeatReadsLoadTheShowtimeOnceThroughTheBookingModule() throws Exception {
        Showtime showtime = create(movie, 8);
        clearInvocations(repository);

        expectFirstSeat(showtime, "AVAILABLE");
        expectFirstSeat(showtime, "AVAILABLE");

        verify(repository, times(1)).findById(showtime.id());
    }

    @Test
    void createdShowtimeIsVisibleOnTheNextRead() throws Exception {
        expectScheduleSize(movie, 0);

        mvc.perform(post("/api/v1/admin/showtimes").with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"movieId":"%s","auditoriumId":"%s","startsAt":"%s","basePrice":90000}
                        """.formatted(movie, room.id(), at(8)))).andExpect(status().isCreated());

        expectScheduleSize(movie, 1);
    }

    @Test
    void cancelledShowtimeDisappearsFromScheduleAndSnapshotOnTheNextRead() throws Exception {
        Showtime showtime = create(movie, 8);
        expectScheduleSize(movie, 1);
        assertSnapshotStatus(showtime, ShowtimeState.SCHEDULED);

        mvc.perform(delete("/api/v1/admin/showtimes/{id}", showtime.id()).with(user("admin").roles("ADMIN")))
                .andExpect(status().isNoContent());

        expectScheduleSize(movie, 0);
        assertSnapshotStatus(showtime, ShowtimeState.CANCELLED);
    }

    @Test
    void showtimesAroundUtcMidnightEvictTheirOwnDate() throws Exception {
        LocalDate nextDate = date.plusDays(1);
        expectScheduleSize(movie, date, 0);
        expectScheduleSize(movie, nextDate, 0);

        create(movie, 21);
        showtimes.create(new CreateShowtimeCommand(movie, room.id(),
                nextDate.atTime(0, 0).toInstant(ZoneOffset.UTC), new BigDecimal("90000.00")));

        expectScheduleSize(movie, date, 1);
        expectScheduleSize(movie, nextDate, 1);
    }

    @Test
    void writeKeepsCachedSchedulesOfOtherMovies() throws Exception {
        expectScheduleSize(otherMovie, 0);
        clearInvocations(repository);

        create(movie, 8);
        expectScheduleSize(otherMovie, 0);

        verify(repository, never()).findByMovieIdAndStartsAtBetween(eq(otherMovie), any(), any());
    }

    @Test
    void bookedSeatsAreNeverCached() throws Exception {
        Showtime showtime = create(movie, 8);
        expectFirstSeat(showtime, "AVAILABLE");

        UUID bookingId = bookings.createBooking(userId, showtime.id(), List.of(firstSeat())).getBookingId();
        expectFirstSeat(showtime, "BOOKED");

        bookings.cancelBooking(userId, bookingId);
        expectFirstSeat(showtime, "AVAILABLE");
    }

    @Test
    void rejectedCancellationKeepsServingTheScheduledShowtime() throws Exception {
        Showtime showtime = create(movie, 8);
        expectScheduleSize(movie, 1);
        bookings.createBooking(userId, showtime.id(), List.of(firstSeat()));

        assertThatThrownBy(() -> showtimes.cancel(showtime.id())).isInstanceOf(ShowtimeHasBookingsException.class);

        expectScheduleSize(movie, 1);
        assertSnapshotStatus(showtime, ShowtimeState.SCHEDULED);
    }

    @Test
    void lockMethodsReadTheDatabaseEvenWhenTheSnapshotIsCached() {
        Showtime showtime = create(movie, 8);
        assertSnapshotStatus(showtime, ShowtimeState.SCHEDULED);
        // Bypasses the service on purpose, so the cached snapshot is now out of date.
        jdbc.update("update showtimes set status = 'CANCELLED' where id = ?", showtime.id());
        assertSnapshotStatus(showtime, ShowtimeState.SCHEDULED);

        var transaction = new TransactionTemplate(transactionManager);
        assertThat(transaction.execute(tx -> showtimeModuleApi.lockShowtimeForBooking(showtime.id())).status())
                .isEqualTo(ShowtimeState.CANCELLED);
        assertThat(transaction.execute(tx -> showtimeModuleApi.lockShowtimeShareForBooking(showtime.id())).status())
                .isEqualTo(ShowtimeState.CANCELLED);
        assertThatThrownBy(() -> bookings.createBooking(userId, showtime.id(), List.of(firstSeat())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownAndNullShowtimesStayEmpty() {
        UUID unknown = UUID.randomUUID();
        assertThat(showtimeModuleApi.findShowtime(unknown)).isEmpty();
        assertThat(showtimeModuleApi.findShowtime(unknown)).isEmpty();
        assertThat(showtimeModuleApi.findShowtime(null)).isEmpty();
    }

    @Test
    void snapshotLoadStartedBeforeCancellationCannotLeaveOldDataBehind() throws Exception {
        Showtime showtime = create(movie, 8);
        var slow = new SlowRead();
        doAnswer(slow).when(repository).findById(showtime.id());

        Future<?> reader = executor.submit(() -> showtimeModuleApi.findShowtime(showtime.id()));
        slow.awaitRead();
        Future<?> writer = executor.submit(() -> showtimes.cancel(showtime.id()));
        awaitCommittedCancellation(showtime);
        slow.finish(reader, writer);

        assertSnapshotStatus(showtime, ShowtimeState.CANCELLED);
    }

    @Test
    void scheduleLoadStartedBeforeCancellationCannotLeaveOldDataBehind() throws Exception {
        Showtime showtime = create(movie, 8);
        var slow = new SlowRead();
        doAnswer(slow).when(repository).findByMovieIdAndStartsAtBetween(eq(movie), any(), any());

        Future<?> reader = executor.submit(() -> showtimes.findByMovieIdAndDate(movie, date));
        slow.awaitRead();
        Future<?> writer = executor.submit(() -> showtimes.cancel(showtime.id()));
        awaitCommittedCancellation(showtime);
        slow.finish(reader, writer);

        expectScheduleSize(movie, 0);
    }

    @Test
    void scheduleLoadStartedBeforeCreationCannotHideTheNewShowtime() throws Exception {
        var slow = new SlowRead();
        doAnswer(slow).when(repository).findByMovieIdAndStartsAtBetween(eq(movie), any(), any());

        Future<?> reader = executor.submit(() -> showtimes.findByMovieIdAndDate(movie, date));
        slow.awaitRead();
        Future<?> writer = executor.submit(() -> create(movie, 8));
        await().atMost(LIMIT_SECONDS, TimeUnit.SECONDS).until(() -> jdbc.queryForObject(
                "select count(*) from showtimes where movie_id = ?", Integer.class, movie) == 1);
        slow.finish(reader, writer);

        expectScheduleSize(movie, 1);
    }

    @Test
    void cacheInterceptorRunsOutsideTheTransactionSoEvictionFollowsCommit() {
        List<Class<?>> chain = new ArrayList<>();
        for (var advisor : ((Advised) showtimes).getAdvisors()) {
            chain.add(advisor.getAdvice().getClass());
        }
        assertThat(chain).contains(CacheInterceptor.class, TransactionInterceptor.class);
        assertThat(chain.indexOf(CacheInterceptor.class)).isLessThan(chain.indexOf(TransactionInterceptor.class));
    }

    @Test
    void hitRatioIsExportedForObservability() throws Exception {
        double before = scheduleHits();

        expectScheduleSize(movie, 0);
        expectScheduleSize(movie, 0);

        assertThat(scheduleHits()).isEqualTo(before + 1);
        assertThat(meters.get("cache.gets").tag("cache", ShowtimeService.SHOWTIME_CACHE).functionCounters()).isNotEmpty();
    }

    private double scheduleHits() {
        return meters.get("cache.gets").tag("cache", ShowtimeService.SCHEDULE_CACHE).tag("result", "hit")
                .functionCounter().count();
    }

    private Showtime create(UUID movieId, int hour) {
        return showtimes.create(new CreateShowtimeCommand(movieId, room.id(), at(hour), new BigDecimal("90000.00")));
    }

    private Instant at(int hour) {
        return date.atTime(hour, 0).toInstant(ZoneOffset.UTC);
    }

    private UUID firstSeat() {
        return auditoriums.findSeats(room.id()).getFirst().id();
    }

    private void assertSnapshotStatus(Showtime showtime, ShowtimeState expected) {
        assertThat(showtimeModuleApi.findShowtime(showtime.id()).orElseThrow().status()).isEqualTo(expected);
    }

    private void expectScheduleSize(UUID movieId, int size) throws Exception {
        expectScheduleSize(movieId, date, size);
    }

    private void expectScheduleSize(UUID movieId, LocalDate day, int size) throws Exception {
        mvc.perform(get("/api/v1/showtimes").param("movieId", movieId.toString()).param("date", day.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(size));
    }

    private void expectFirstSeat(Showtime showtime, String expected) throws Exception {
        mvc.perform(get("/api/v1/showtimes/{id}/seats", showtime.id()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value(expected));
    }

    private void awaitCommittedCancellation(Showtime showtime) {
        await().atMost(LIMIT_SECONDS, TimeUnit.SECONDS).until(() -> ShowtimeStatus.CANCELLED.name().equals(
                jdbc.queryForObject("select status from showtimes where id = ?", String.class, showtime.id())));
    }

    private UUID insertMovie() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO movies (id,title,description,duration_minutes,age_rating,release_date,status,created_at,updated_at)
                VALUES (?, 'Cache Movie', 'Test fixture', 120, 'P', CURRENT_DATE, 'NOW_SHOWING', now(), now())
                """, id);
        return id;
    }

    /** Reads the real value, then holds it back until the concurrent write has committed. */
    private static final class SlowRead implements Answer<Object> {
        private final CountDownLatch read = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public Object answer(InvocationOnMock invocation) throws Throwable {
            Object value = invocation.callRealMethod();
            if (read.getCount() > 0) {
                read.countDown();
                assertThat(release.await(LIMIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            }
            return value;
        }

        void awaitRead() throws InterruptedException {
            assertThat(read.await(LIMIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }

        void finish(Future<?> reader, Future<?> writer) throws Exception {
            // Lets an eviction that does not wait for the load run first, so a regression is caught.
            Thread.sleep(300);
            release.countDown();
            reader.get(LIMIT_SECONDS, TimeUnit.SECONDS);
            writer.get(LIMIT_SECONDS, TimeUnit.SECONDS);
        }
    }
}
