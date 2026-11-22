package com.example.cinema.modules.booking.business;

import java.security.SecureRandom;

public final class BookingCodeGenerator {

    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private BookingCodeGenerator() {
    }

    public static String generate() {
        char[] code = new char[10];
        for (int i = 0; i < code.length; i++) {
            code[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(code);
    }
}
