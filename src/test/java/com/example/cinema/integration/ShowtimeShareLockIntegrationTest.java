package com.example.cinema.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.example.cinema.modules.theater.business.Auditorium;
import com.example.cinema.modules.theater.business.AuditoriumService;
import com.example.cinema.modules.theater.business.CreateAuditoriumCommand;
import com.example.cinema.modules.theater.business.CreateShowtimeCommand;
import com.example.cinema.modules.theater.business.SeatType;
import com.example.cinema.modules.theater.business.Showtime;
import com.example.cinema.modules.theater.business.ShowtimeHasBookingsException;
import com.example.cinema.modules.theater.business.ShowtimeModuleApi.ShowtimeSnapshot;
import com.example.cinema.modules.theater.business.ShowtimeModuleApi.ShowtimeState;
import com.example.cinema.modules.theater.business.ShowtimeService;
import com.example.cinema.modules.theater.business.ShowtimeStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 2, experiment E1, plan B: a booking transaction holds {@code FOR SHARE} on the showtime row
 * while an Admin cancels the showtime ({@code FOR UPDATE}). These tests drive two real PostgreSQL
 * transactions from separate threads to prove that:
 * <ul>
 *   <li>share locks do not block each other (the reason plan B can beat plan A),</li>
 *   <li>a cancellation waits for every in-flight booking and never overtakes it,</li>
 *   <li>a cancellation that wins the race is visible to a booking that was waiting on the share lock.</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class ShowtimeShareLockIntegrationTest {

    private static final Duration BLOCKED_PROBE = Duration.ofMillis(700);
    private static final Duration COMPLETION_LIMIT = Duration.ofSeconds(10);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired AuditoriumService auditoriums;
    @Autowired ShowtimeService showtimes;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private Showtime showtime;
    private UUID seatId;
    private UUID userId;

    @BeforeEach
    void seed() {
        UUID movieId = UUID.randomUUID();
        userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO movies (id,title,description,duration_minutes,age_rating,release_date,status,created_at,updated_at)
                VALUES (?, 'Share Lock Movie', 'Test fixture', 120, 'P', CURRENT_DATE, 'NOW_SHOWING', now(), now())
                """, movieId);
        jdbc.update("INSERT INTO users (id,username,password_hash,role,created_at,updated_at) VALUES (?,?,'test','CUSTOMER',now(),now())",
                userId, "share-lock-" + userId);
        Auditorium room = auditoriums.create(new CreateAuditoriumCommand("ShareLock " + UUID.randomUUID(),
                List.of(new CreateAuditoriumCommand.SeatDefinition("A", 1, 2, SeatType.STANDARD))));
        seatId = auditoriums.findSeats(room.id()).getFirst().id();
        Instant startsAt = Instant.now().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.SECONDS);
        showtime = showtimes.create(new CreateShowtimeCommand(movieId, room.id(), startsAt, new BigDecimal("90000.00")));
    }

    @AfterEach
    void stopThreads() {
        executor.shutdownNow();
    }

    @Test
    void shareLocksFromDifferentTransactionsDoNotBlockEachOther() throws Exception {
        try (HeldTransaction holder = holdTransaction(() -> showtimes.lockShowtimeShareForBooking(showtime.id()))) {
            Future<ShowtimeSnapshot> second = inTransaction(() -> showtimes.lockShowtimeShareForBooking(showtime.id()));

            // The second share lock must be granted while the first transaction is still open.
            ShowtimeSnapshot snapshot = second.get(COMPLETION_LIMIT.toSeconds(), TimeUnit.SECONDS);

            assertThat(snapshot.showtimeId()).isEqualTo(showtime.id());
            assertThat(holder.isStillOpen()).isTrue();
        }
    }

    @Test
    void cancellationWaitsForShareLockHolderAndSucceedsWhenNoBookingWasWritten() throws Exception {
        try (HeldTransaction holder = holdTransaction(() -> showtimes.lockShowtimeShareForBooking(showtime.id()))) {
            Future<Void> cancellation = executor.submit(() -> {
                showtimes.cancel(showtime.id());
                return null;
            });

            assertStaysBlocked(cancellation);
            awaitBackendWaitingOnLock();
            assertThat(showtimes.findById(showtime.id()).status()).isEqualTo(ShowtimeStatus.SCHEDULED);

            holder.commit();
            cancellation.get(COMPLETION_LIMIT.toSeconds(), TimeUnit.SECONDS);
        }

        assertThat(showtimes.findById(showtime.id()).status()).isEqualTo(ShowtimeStatus.CANCELLED);
    }

    @Test
    void cancellationIsRejectedWhenTheShareLockHolderCommitsABooking() throws Exception {
        try (HeldTransaction holder = holdTransaction(() -> {
            showtimes.lockShowtimeShareForBooking(showtime.id());
            insertConfirmedBooking();
            return null;
        })) {
            Future<Void> cancellation = executor.submit(() -> {
                showtimes.cancel(showtime.id());
                return null;
            });

            assertStaysBlocked(cancellation);

            holder.commit();
            assertThatThrownBy(() -> cancellation.get(COMPLETION_LIMIT.toSeconds(), TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ShowtimeHasBookingsException.class);
        }

        assertThat(showtimes.findById(showtime.id()).status()).isEqualTo(ShowtimeStatus.SCHEDULED);
        assertThat(jdbc.queryForObject("select count(*) from bookings where showtime_id = ? and status = 'CONFIRMED'",
                Integer.class, showtime.id())).isEqualTo(1);
    }

    @Test
    void bookingWaitingOnShareLockSeesCancellationCommittedByAdmin() throws Exception {
        // The Admin transaction owns FOR UPDATE and has not committed yet.
        try (HeldTransaction admin = holdTransaction(() -> showtimes.lockShowtimeForBooking(showtime.id()))) {
            Future<ShowtimeSnapshot> booking = inTransaction(() -> showtimes.lockShowtimeShareForBooking(showtime.id()));

            assertStaysBlocked(booking);
            awaitBackendWaitingOnLock();

            admin.commitAfter(() -> jdbc.update("update showtimes set status = 'CANCELLED' where id = ?", showtime.id()));

            ShowtimeSnapshot snapshot = booking.get(COMPLETION_LIMIT.toSeconds(), TimeUnit.SECONDS);
            assertThat(snapshot.status()).isEqualTo(ShowtimeState.CANCELLED);
        }
    }

    private void insertConfirmedBooking() {
        UUID bookingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO bookings (id,user_id,showtime_id,total_amount,created_at,payment_status,status,booking_code)
                VALUES (?,?,?,90000.00,now(),'PENDING','CONFIRMED',?)
                """, bookingId, userId, showtime.id(), "SL" + bookingId.toString().substring(0, 8));
        jdbc.update("INSERT INTO booking_seats (id,booking_id,showtime_id,seat_id,price) VALUES (?,?,?,?,90000.00)",
                UUID.randomUUID(), bookingId, showtime.id(), seatId);
    }

    private void assertStaysBlocked(Future<?> future) {
        assertThatThrownBy(() -> future.get(BLOCKED_PROBE.toMillis(), TimeUnit.MILLISECONDS))
                .as("operation should be waiting for the row lock")
                .isInstanceOf(TimeoutException.class);
    }

    private void awaitBackendWaitingOnLock() {
        await().atMost(COMPLETION_LIMIT).untilAsserted(() ->
                assertThat(jdbc.queryForObject("""
                        select count(*) from pg_stat_activity
                        where datname = current_database() and wait_event_type = 'Lock'
                        """, Integer.class)).isGreaterThanOrEqualTo(1));
    }

    private <T> Future<T> inTransaction(Supplier<T> work) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        return executor.submit(() -> template.execute(status -> work.get()));
    }

    /** Runs {@code work} in a transaction on its own thread and keeps that transaction open until told to commit. */
    private HeldTransaction holdTransaction(Supplier<?> work) throws InterruptedException {
        HeldTransaction held = new HeldTransaction();
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        held.outcome = executor.submit(() -> template.execute(status -> {
            work.get();
            held.locked.countDown();
            try {
                if (!held.release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Held transaction was never released");
                }
                Runnable beforeCommit = held.beforeCommit;
                if (beforeCommit != null) {
                    beforeCommit.run();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return null;
        }));
        assertThat(held.locked.await(COMPLETION_LIMIT.toSeconds(), TimeUnit.SECONDS))
                .as("transaction should have acquired its lock").isTrue();
        return held;
    }

    private static final class HeldTransaction implements AutoCloseable {
        final CountDownLatch locked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile Runnable beforeCommit;
        Future<?> outcome;

        boolean isStillOpen() {
            return !outcome.isDone();
        }

        void commit() throws Exception {
            release.countDown();
            outcome.get(COMPLETION_LIMIT.toSeconds(), TimeUnit.SECONDS);
        }

        /** Runs {@code lastStatement} inside the held transaction, then commits it. */
        void commitAfter(Runnable lastStatement) throws Exception {
            beforeCommit = lastStatement;
            commit();
        }

        @Override
        public void close() {
            release.countDown();
        }
    }
}
