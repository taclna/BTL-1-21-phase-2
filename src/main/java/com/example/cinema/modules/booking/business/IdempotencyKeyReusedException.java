package com.example.cinema.modules.booking.business;

public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException() {
        super("Idempotency-Key has already been used with a different payload");
    }
}
