package com.example.cinema.modules.booking.business;

public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException() {
        super("Idempotency-Key must be a valid UUID");
    }
}
