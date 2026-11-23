package com.example.cinema.modules.booking.data;

import com.example.cinema.modules.booking.business.BookingIdempotency;
import com.example.cinema.modules.booking.business.BookingIdempotencyRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "cinema.booking.enabled", havingValue = "true", matchIfMissing = true)
class BookingIdempotencyRepositoryAdapter implements BookingIdempotencyRepository {

    private static final String CLAIM_SQL = """
            INSERT INTO booking_idempotency (
                user_id, idempotency_key, request_hash, booking_id, created_at, expires_at
            )
            VALUES (?, ?, ?, NULL, now(), now() + interval '24 hours')
            ON CONFLICT (user_id, idempotency_key) DO UPDATE
            SET request_hash = EXCLUDED.request_hash,
                booking_id = NULL,
                created_at = EXCLUDED.created_at,
                expires_at = EXCLUDED.expires_at
            WHERE booking_idempotency.expires_at < now()
            RETURNING user_id
            """;

    private final JdbcTemplate jdbcTemplate;

    BookingIdempotencyRepositoryAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean claim(UUID userId, UUID idempotencyKey, String requestHash) {
        return !jdbcTemplate.query(
                CLAIM_SQL,
                (resultSet, rowNumber) -> resultSet.getObject("user_id", UUID.class),
                userId,
                idempotencyKey,
                requestHash
        ).isEmpty();
    }

    @Override
    public Optional<BookingIdempotency> find(UUID userId, UUID idempotencyKey) {
        return jdbcTemplate.query(
                """
                        SELECT request_hash, booking_id
                        FROM booking_idempotency
                        WHERE user_id = ? AND idempotency_key = ?
                        """,
                BookingIdempotencyRepositoryAdapter::mapRecord,
                userId,
                idempotencyKey
        ).stream().findFirst();
    }

    @Override
    public void attachBooking(
            UUID userId,
            UUID idempotencyKey,
            String requestHash,
            UUID bookingId
    ) {
        int updated = jdbcTemplate.update(
                """
                        UPDATE booking_idempotency
                        SET booking_id = ?
                        WHERE user_id = ?
                          AND idempotency_key = ?
                          AND request_hash = ?
                          AND booking_id IS NULL
                        """,
                bookingId,
                userId,
                idempotencyKey,
                requestHash
        );
        if (updated != 1) {
            throw new IllegalStateException("Idempotency claim could not be linked to its booking");
        }
    }

    private static BookingIdempotency mapRecord(ResultSet resultSet, int rowNumber) throws SQLException {
        return new BookingIdempotency(
                resultSet.getString("request_hash"),
                resultSet.getObject("booking_id", UUID.class)
        );
    }
}
