package com.example.cinema.integration;

import com.example.cinema.config.JwtTokenService;
import com.example.cinema.modules.identity.business.AuthenticatedUser;
import com.example.cinema.modules.identity.business.User;
import com.example.cinema.modules.theater.business.Auditorium;
import com.example.cinema.modules.theater.business.AuditoriumService;
import com.example.cinema.modules.theater.business.CreateAuditoriumCommand;
import com.example.cinema.modules.theater.business.CreateShowtimeCommand;
import com.example.cinema.modules.theater.business.SeatType;
import com.example.cinema.modules.theater.business.Showtime;
import com.example.cinema.modules.theater.business.ShowtimeService;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BookingApiIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuditoriumService auditoriumService;

    @Autowired
    private ShowtimeService showtimeService;

    @LocalServerPort
    private int serverPort;

    private UUID firstCustomer;
    private UUID secondCustomer;
    private Showtime showtime;
    private UUID seatId;
    private UUID secondSeatId;

    @BeforeEach
    void seed() {
        firstCustomer = insertCustomer();
        secondCustomer = insertCustomer();
        UUID movieId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO movies (id,title,description,duration_minutes,age_rating,release_date,status,created_at,updated_at)
                VALUES (?, ?, 'Booking API integration fixture', 120, 'P', CURRENT_DATE, 'NOW_SHOWING', now(), now())
                """, movieId, "Booking API " + movieId);

        Auditorium auditorium = auditoriumService.create(new CreateAuditoriumCommand(
                "Booking API " + UUID.randomUUID(),
                List.of(new CreateAuditoriumCommand.SeatDefinition("A", 1, 2, SeatType.STANDARD))));
        Instant startsAt = Instant.now().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.SECONDS);
        showtime = showtimeService.create(new CreateShowtimeCommand(
                movieId, auditorium.id(), startsAt, new BigDecimal("90000.00")));
        List<com.example.cinema.modules.theater.business.Seat> seats = auditoriumService.findSeats(auditorium.id());
        seatId = seats.get(0).id();
        secondSeatId = seats.get(1).id();
    }

    @Test
    void retryWithTheSameKeyAndPayloadReplaysTheCreatedBooking() {
        String key = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> created = createBooking(firstCustomer, List.of(seatId), key);
        ResponseEntity<JsonNode> replayed = createBooking(firstCustomer, List.of(seatId), key);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(replayed.getBody()).isNotNull();
        assertThat(created.getBody()).isNotNull();
        assertThat(replayed.getBody().path("data").path("bookingId").asText())
                .isEqualTo(created.getBody().path("data").path("bookingId").asText());
        assertThat(countBookingsForShowtime()).isEqualTo(1);
    }

    @Test
    void retryAfterClientDisconnectsBeforeReadingResponseReplaysCommittedBooking() throws Exception {
        String key = UUID.randomUUID().toString();
        String token = jwtTokenService.issue(new AuthenticatedUser(firstCustomer, User.Role.CUSTOMER));
        String body = "{\"showtimeId\":\"" + showtime.id() + "\",\"seatIds\":[\"" + seatId + "\"]}";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String request = "POST /api/v1/bookings HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + serverPort + "\r\n"
                + "Authorization: Bearer " + token + "\r\n"
                + "Idempotency-Key: " + key + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Connection: close\r\n\r\n";

        // Send a complete request, then disconnect without reading the response.
        // The server may have committed before it learns that the client is gone.
        try (Socket socket = new Socket("127.0.0.1", serverPort)) {
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(bodyBytes);
            socket.getOutputStream().flush();
            socket.shutdownOutput();
        }

        waitForCommittedBooking(firstCustomer, key);

        ResponseEntity<JsonNode> replayed = createBooking(firstCustomer, List.of(seatId), key);

        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(replayed.getBody()).isNotNull();
        assertThat(replayed.getBody().path("data").path("bookingId").asText()).isNotBlank();
        assertThat(countBookingsForShowtime()).isEqualTo(1);
    }

    @Test
    void reusingAKeyWithADifferentPayloadReturnsConflict() {
        String key = UUID.randomUUID().toString();
        assertThat(createBooking(firstCustomer, List.of(seatId), key).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> reused = createBooking(firstCustomer, List.of(secondSeatId), key);

        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(reused.getBody()).isNotNull();
        assertThat(reused.getBody().path("error").path("code").asText())
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(reused.getBody().path("error").path("requestId").asText()).isNotBlank();
        assertThat(countBookingsForShowtime()).isEqualTo(1);
    }

    @Test
    void concurrentRequestsWithTheSameKeyReplayOneBooking() throws Exception {
        String key = UUID.randomUUID().toString();

        List<ResponseEntity<JsonNode>> responses = race(
                () -> createBooking(firstCustomer, List.of(seatId), key),
                () -> createBooking(firstCustomer, List.of(seatId), key));

        assertThat(responses.stream().map(ResponseEntity::getStatusCode).toList())
                .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.OK);
        assertThat(responses.stream()
                .map(response -> response.getBody().path("data").path("bookingId").asText())
                .distinct().toList()).hasSize(1);
        assertThat(responses.stream().filter(response -> response.getStatusCode() == HttpStatus.OK)
                .findFirst().orElseThrow().getHeaders().getFirst("Idempotency-Replayed"))
                .isEqualTo("true");
        assertThat(countBookingsForShowtime()).isEqualTo(1);
    }

    @Test
    void failedBookingRollsBackTheClaimSoTheSameKeyCanRetry() {
        ResponseEntity<JsonNode> blocker = createBooking(secondCustomer, seatId);
        assertThat(blocker.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID blockerId = UUID.fromString(blocker.getBody().path("data").path("bookingId").asText());
        String retryKey = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> failed = createBooking(firstCustomer, List.of(seatId), retryKey);
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(failed.getBody().path("error").path("code").asText())
                .isEqualTo("SEAT_ALREADY_BOOKED");
        assertThat(countIdempotencyRecords(firstCustomer, retryKey)).isZero();

        assertThat(cancelBooking(secondCustomer, blockerId).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<JsonNode> retried = createBooking(firstCustomer, List.of(seatId), retryKey);

        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(countIdempotencyRecords(firstCustomer, retryKey)).isEqualTo(1);
    }

    @Test
    void theSameKeyIsScopedIndependentlyForEachUser() {
        String key = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> first = createBooking(firstCustomer, List.of(seatId), key);
        ResponseEntity<JsonNode> second = createBooking(secondCustomer, List.of(secondSeatId), key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(countBookingsForShowtime()).isEqualTo(2);
    }

    @Test
    void missingOrMalformedIdempotencyKeyReturnsDocumentedBadRequest() {
        ResponseEntity<JsonNode> missing = createBooking(firstCustomer, List.of(seatId), null);
        ResponseEntity<JsonNode> malformed = createBooking(firstCustomer, List.of(seatId), "not-a-uuid");

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getBody().path("error").path("code").asText())
                .isEqualTo("INVALID_IDEMPOTENCY_KEY");
        assertThat(malformed.getBody().path("error").path("code").asText())
                .isEqualTo("INVALID_IDEMPOTENCY_KEY");
        assertThat(countBookingsForShowtime()).isZero();
    }

    @Test
    void seatOrderDoesNotChangeTheCanonicalRequestPayload() {
        String key = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> created = createBooking(
                firstCustomer, List.of(seatId, secondSeatId), key);
        ResponseEntity<JsonNode> replayed = createBooking(
                firstCustomer, List.of(secondSeatId, seatId), key);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getBody().path("data").path("bookingId").asText())
                .isEqualTo(created.getBody().path("data").path("bookingId").asText());
        assertThat(countBookingsForShowtime()).isEqualTo(1);
    }

    @Test
    void retryAfterCancellationReplaysTheCurrentCancelledBooking() {
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> created = createBooking(firstCustomer, List.of(seatId), key);
        UUID bookingId = UUID.fromString(created.getBody().path("data").path("bookingId").asText());
        assertThat(cancelBooking(firstCustomer, bookingId).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<JsonNode> replayed = createBooking(firstCustomer, List.of(seatId), key);

        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getBody().path("data").path("bookingId").asText())
                .isEqualTo(bookingId.toString());
        assertThat(replayed.getBody().path("data").path("status").asText()).isEqualTo("CANCELLED");
        assertThat(countBookingsForShowtime()).isEqualTo(1);
    }

    @Test
    void expiredKeyCanBeClaimedAgainBeforeCleanupRuns() {
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> original = createBooking(firstCustomer, List.of(seatId), key);
        UUID originalId = UUID.fromString(original.getBody().path("data").path("bookingId").asText());
        assertThat(cancelBooking(firstCustomer, originalId).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        jdbc.update("""
                UPDATE booking_idempotency
                SET created_at = now() - interval '2 days',
                    expires_at = now() - interval '1 day'
                WHERE user_id = ? AND idempotency_key = ?
                """, firstCustomer, UUID.fromString(key));

        ResponseEntity<JsonNode> afterExpiry = createBooking(firstCustomer, List.of(seatId), key);

        assertThat(afterExpiry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(afterExpiry.getBody().path("data").path("bookingId").asText())
                .isNotEqualTo(originalId.toString());
        assertThat(countBookingsForShowtime()).isEqualTo(2);
        assertThat(countIdempotencyRecords(firstCustomer, key)).isEqualTo(1);
    }

    @Test
    void twoCustomersBookingTheSameSeatReturnOneCreatedAndOneConflict() throws Exception {
        List<ResponseEntity<JsonNode>> responses = race(
                () -> createBooking(firstCustomer, seatId),
                () -> createBooking(secondCustomer, seatId));

        assertThat(responses.stream().map(response -> response.getStatusCode().value()).toList())
                .containsExactlyInAnyOrder(HttpStatus.CREATED.value(), HttpStatus.CONFLICT.value());

        ResponseEntity<JsonNode> winner = responses.stream()
                .filter(response -> response.getStatusCode() == HttpStatus.CREATED)
                .findFirst().orElseThrow();
        ResponseEntity<JsonNode> loser = responses.stream()
                .filter(response -> response.getStatusCode() == HttpStatus.CONFLICT)
                .findFirst().orElseThrow();
        assertThat(winner.getBody()).isNotNull();
        assertThat(winner.getBody().path("data").path("bookingId").asText()).isNotBlank();
        assertThat(loser.getBody()).isNotNull();
        assertThat(loser.getBody().path("error").path("code").asText())
                .isEqualTo("SEAT_ALREADY_BOOKED");
        assertThat(loser.getBody().path("error").path("requestId").asText()).isNotBlank();

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM booking_seats
                WHERE showtime_id = ? AND seat_id = ? AND released_at IS NULL
                """, Integer.class, showtime.id(), seatId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM bookings
                WHERE showtime_id = ? AND status = 'CONFIRMED'
                """, Integer.class, showtime.id())).isEqualTo(1);
    }

    @Test
    void bookingEndpointsCoverCreateListDetailSeatsAndCancellation() {
        ResponseEntity<JsonNode> created = createBooking(firstCustomer, seatId);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        UUID bookingId = UUID.fromString(created.getBody().path("data").path("bookingId").asText());
        assertThat(created.getBody().path("data").path("bookingCode").asText())
                .matches("[A-Z2-7]{10}");
        assertThat(created.getBody().path("data").path("status").asText()).isEqualTo("CONFIRMED");

        ResponseEntity<JsonNode> list = get("/api/v1/bookings", firstCustomer);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).isNotNull();
        assertThat(list.getBody().path("data").isArray()).isTrue();
        assertThat(list.getBody().path("data").get(0).path("bookingId").asText())
                .isEqualTo(bookingId.toString());
        assertThat(list.getBody().path("meta").path("page").asInt()).isEqualTo(1);
        assertThat(list.getBody().path("meta").path("pageSize").asInt()).isEqualTo(20);
        assertThat(list.getBody().path("meta").path("totalItems").asLong()).isGreaterThanOrEqualTo(1);

        ResponseEntity<JsonNode> detail = get("/api/v1/bookings/" + bookingId, firstCustomer);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody()).isNotNull();
        assertThat(detail.getBody().path("data").path("seats").get(0).path("seatId").asText())
                .isEqualTo(seatId.toString());

        assertSeatStatus("BOOKED");

        ResponseEntity<Void> cancelled = restTemplate.exchange(
                "/api/v1/bookings/" + bookingId,
                HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(firstCustomer)),
                Void.class);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<JsonNode> cancelledDetail = get("/api/v1/bookings/" + bookingId, firstCustomer);
        assertThat(cancelledDetail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelledDetail.getBody()).isNotNull();
        assertThat(cancelledDetail.getBody().path("data").path("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelledDetail.getBody().path("data").path("cancelledAt").asText()).isNotBlank();
        assertSeatStatus("AVAILABLE");
    }

    @Test
    void bookingEndpointsRequireAuthenticationAndHideOtherCustomersBookings() {
        ResponseEntity<JsonNode> unauthenticated = restTemplate.getForEntity(
                "/api/v1/bookings", JsonNode.class);
        assertThat(unauthenticated.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unauthenticated.getBody()).isNotNull();
        assertThat(unauthenticated.getBody().path("error").path("code").asText())
                .isEqualTo("UNAUTHORIZED");

        ResponseEntity<JsonNode> created = createBooking(firstCustomer, seatId);
        assertThat(created.getBody()).isNotNull();
        String bookingId = created.getBody().path("data").path("bookingId").asText();

        ResponseEntity<JsonNode> otherCustomerDetail = get(
                "/api/v1/bookings/" + bookingId, secondCustomer);
        assertThat(otherCustomerDetail.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(otherCustomerDetail.getBody()).isNotNull();
        assertThat(otherCustomerDetail.getBody().path("error").path("code").asText())
                .isEqualTo("BOOKING_NOT_FOUND");

        ResponseEntity<JsonNode> otherCustomerCancellation = restTemplate.exchange(
                "/api/v1/bookings/" + bookingId,
                HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(secondCustomer)),
                JsonNode.class);
        assertThat(otherCustomerCancellation.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(otherCustomerCancellation.getBody()).isNotNull();
        assertThat(otherCustomerCancellation.getBody().path("error").path("code").asText())
                .isEqualTo("BOOKING_NOT_FOUND");

        ResponseEntity<JsonNode> missingShowtime = restTemplate.getForEntity(
                "/api/v1/showtimes/" + UUID.randomUUID() + "/seats", JsonNode.class);
        assertThat(missingShowtime.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missingShowtime.getBody()).isNotNull();
        assertThat(missingShowtime.getBody().path("error").path("code").asText())
                .isEqualTo("SHOWTIME_NOT_FOUND");
    }

    @Test
    void adminCanListAndViewEveryCustomersBooking() {
        UUID adminId = insertAdmin();
        ResponseEntity<JsonNode> created = createBooking(firstCustomer, seatId);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID bookingId = UUID.fromString(created.getBody().path("data").path("bookingId").asText());

        ResponseEntity<JsonNode> denied = restTemplate.exchange(
                "/api/v1/admin/bookings",
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(firstCustomer)),
                JsonNode.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<JsonNode> list = restTemplate.exchange(
                "/api/v1/admin/bookings?page=1&pageSize=20",
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(adminId, User.Role.ADMIN)),
                JsonNode.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody().path("data").isArray()).isTrue();
        assertThat(list.getBody().path("data").get(0).path("bookingId").asText())
                .isEqualTo(bookingId.toString());
        assertThat(list.getBody().path("meta").path("page").asInt()).isEqualTo(1);
        assertThat(list.getBody().path("meta").path("pageSize").asInt()).isEqualTo(20);

        ResponseEntity<JsonNode> detail = restTemplate.exchange(
                "/api/v1/admin/bookings/" + bookingId,
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(adminId, User.Role.ADMIN)),
                JsonNode.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody().path("data").path("bookingId").asText())
                .isEqualTo(bookingId.toString());
    }

    @Test
    void adminCanCancelAnotherCustomersBooking() {
        UUID adminId = insertAdmin();
        ResponseEntity<JsonNode> created = createBooking(firstCustomer, seatId);
        UUID bookingId = UUID.fromString(created.getBody().path("data").path("bookingId").asText());

        ResponseEntity<Void> cancelled = restTemplate.exchange(
                "/api/v1/admin/bookings/" + bookingId,
                HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(adminId, User.Role.ADMIN)),
                Void.class);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<JsonNode> detail = restTemplate.exchange(
                "/api/v1/admin/bookings/" + bookingId,
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(adminId, User.Role.ADMIN)),
                JsonNode.class);
        assertThat(detail.getBody().path("data").path("status").asText())
                .isEqualTo("CANCELLED");
    }

    @Test
    void prometheusExportsRuntimeAndBookingBusinessMetrics() {
        String before = prometheusMetrics();
        double bookingsBefore = metricValue(before, "booking_creation_total");
        double conflictsBefore = metricValue(before, "booking_seat_conflict_total");
        double idempotencyCreatedBefore = metricValue(before, "booking_idempotency_total");
        double idempotencyReplayedBefore = metricValue(before, "booking_idempotency_replayed_total");
        double idempotencyReusedBefore = metricValue(before, "booking_idempotency_reused_total");

        String key = UUID.randomUUID().toString();
        assertThat(createBooking(firstCustomer, List.of(seatId), key).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(createBooking(firstCustomer, List.of(seatId), key).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(createBooking(firstCustomer, List.of(secondSeatId), key).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(createBooking(secondCustomer, seatId).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        String metrics = prometheusMetrics();
        assertThat(metricValue(metrics, "booking_creation_total")).isEqualTo(bookingsBefore + 1);
        assertThat(metricValue(metrics, "booking_seat_conflict_total")).isEqualTo(conflictsBefore + 1);
        assertThat(metricValue(metrics, "booking_idempotency_total"))
                .isEqualTo(idempotencyCreatedBefore + 1);
        assertThat(metricValue(metrics, "booking_idempotency_replayed_total"))
                .isEqualTo(idempotencyReplayedBefore + 1);
        assertThat(metricValue(metrics, "booking_idempotency_reused_total"))
                .isEqualTo(idempotencyReusedBefore + 1);
        assertThat(metrics).contains(
                "http_server_requests_seconds_count",
                "hikaricp_connections_active",
                "jvm_memory_used_bytes",
                "jvm_gc_",
                "process_cpu_usage");
    }

    private UUID insertCustomer() {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id,username,password_hash,role,created_at,updated_at)
                VALUES (?, ?, 'test-hash', 'CUSTOMER', now(), now())
                """, userId, "booking-api-" + userId);
        return userId;
    }

    private UUID insertAdmin() {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id,username,password_hash,role,created_at,updated_at)
                VALUES (?, ?, 'test-hash', 'ADMIN', now(), now())
                """, userId, "booking-admin-" + userId);
        return userId;
    }

    private ResponseEntity<JsonNode> createBooking(UUID customerId, UUID requestedSeatId) {
        return createBooking(customerId, List.of(requestedSeatId), UUID.randomUUID().toString());
    }

    private ResponseEntity<JsonNode> createBooking(
            UUID customerId,
            List<UUID> requestedSeatIds,
            String idempotencyKey
    ) {
        HttpHeaders headers = bearerHeaders(customerId);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return restTemplate.postForEntity(
                "/api/v1/bookings",
                new HttpEntity<>(Map.of(
                        "showtimeId", showtime.id(),
                        "seatIds", requestedSeatIds), headers),
                JsonNode.class);
    }

    private ResponseEntity<Void> cancelBooking(UUID customerId, UUID bookingId) {
        return restTemplate.exchange(
                "/api/v1/bookings/" + bookingId,
                HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(customerId)),
                Void.class);
    }

    private int countBookingsForShowtime() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM bookings WHERE showtime_id = ?",
                Integer.class,
                showtime.id());
    }

    private int countIdempotencyRecords(UUID userId, String idempotencyKey) {
        return jdbc.queryForObject(
                """
                        SELECT count(*) FROM booking_idempotency
                        WHERE user_id = ? AND idempotency_key = ?
                        """,
                Integer.class,
                userId,
                UUID.fromString(idempotencyKey));
    }

    private void waitForCommittedBooking(UUID userId, String idempotencyKey) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadlineNanos) {
            if (countBookingsForShowtime() == 1 && countIdempotencyRecords(userId, idempotencyKey) == 1) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Booking did not commit after the client disconnected");
    }

    private ResponseEntity<JsonNode> get(String path, UUID customerId) {
        return restTemplate.exchange(
                path,
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(customerId)),
                JsonNode.class);
    }

    private String prometheusMetrics() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private double metricValue(String metrics, String metricName) {
        Pattern pattern = Pattern.compile("(?m)^" + Pattern.quote(metricName) + " ([0-9.Ee+-]+)$");
        Matcher matcher = pattern.matcher(metrics);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : 0;
    }

    private void assertSeatStatus(String expectedStatus) {
        ResponseEntity<JsonNode> seats = restTemplate.getForEntity(
                "/api/v1/showtimes/" + showtime.id() + "/seats", JsonNode.class);
        assertThat(seats.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(seats.getBody()).isNotNull();
        JsonNode requestedSeat = findSeat(seats.getBody(), seatId);
        assertThat(requestedSeat).isNotNull();
        assertThat(requestedSeat.path("status").asText()).isEqualTo(expectedStatus);
    }

    private JsonNode findSeat(JsonNode seats, UUID requestedSeatId) {
        for (JsonNode seat : seats) {
            if (requestedSeatId.toString().equals(seat.path("id").asText())) {
                return seat;
            }
        }
        return null;
    }

    private HttpHeaders bearerHeaders(UUID customerId) {
        return bearerHeaders(customerId, User.Role.CUSTOMER);
    }

    private HttpHeaders bearerHeaders(UUID userId, User.Role role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtTokenService.issue(
                new AuthenticatedUser(userId, role)));
        return headers;
    }

    private List<ResponseEntity<JsonNode>> race(
            Callable<ResponseEntity<JsonNode>> first,
            Callable<ResponseEntity<JsonNode>> second
    ) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = List.of(first, second).stream().map(action -> executor.submit(() -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("HTTP race did not start");
                }
                return action.call();
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            return List.of(
                    futures.get(0).get(20, TimeUnit.SECONDS),
                    futures.get(1).get(20, TimeUnit.SECONDS));
        }
    }
}
