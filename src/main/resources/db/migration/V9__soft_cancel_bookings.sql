ALTER TABLE bookings
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
    ADD COLUMN cancelled_at TIMESTAMPTZ,
    ADD CONSTRAINT chk_booking_status CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    ADD CONSTRAINT chk_booking_cancellation CHECK (
        (status = 'CONFIRMED' AND cancelled_at IS NULL)
        OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL)
    );

ALTER TABLE bookings ALTER COLUMN status DROP DEFAULT;

ALTER TABLE booking_seats
    ADD COLUMN released_at TIMESTAMPTZ;

ALTER TABLE booking_seats
    DROP CONSTRAINT uq_booking_seat_per_showtime;

CREATE UNIQUE INDEX uq_active_showtime_seat
    ON booking_seats (showtime_id, seat_id)
    WHERE released_at IS NULL;
