CREATE TABLE booking_idempotency (
    user_id UUID NOT NULL,
    idempotency_key UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    booking_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_booking_idempotency PRIMARY KEY (user_id, idempotency_key),
    CONSTRAINT fk_booking_idempotency_user
        FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_booking_idempotency_booking
        FOREIGN KEY (booking_id) REFERENCES bookings(id),
    CONSTRAINT chk_booking_idempotency_hash
        CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_booking_idempotency_expiry
        CHECK (expires_at > created_at)
);

CREATE INDEX idx_booking_idempotency_expires_at
    ON booking_idempotency (expires_at);
