package com.example.cinema.modules.booking.api;

import com.example.cinema.modules.booking.business.BookingNotFoundException;
import com.example.cinema.modules.booking.business.BookingCannotBeCancelledException;
import com.example.cinema.modules.booking.business.SeatAlreadyBookedException;
import com.example.cinema.shared.api.ErrorResponse;
import com.example.cinema.shared.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = BookingController.class)
public class BookingExceptionHandler {

    @ExceptionHandler(SeatAlreadyBookedException.class)
    public ResponseEntity<ErrorResponse> handleSeatAlreadyBooked(HttpServletRequest request) {
        return error(request, HttpStatus.CONFLICT,
                "SEAT_ALREADY_BOOKED", "Seat has already been booked");
    }

    @ExceptionHandler(BookingCannotBeCancelledException.class)
    public ResponseEntity<ErrorResponse> handleBookingCannotBeCancelled(
            BookingCannotBeCancelledException exception,
            HttpServletRequest request
    ) {
        return error(request, HttpStatus.CONFLICT,
                "BOOKING_CANNOT_BE_CANCELLED", "Booking cannot be cancelled");
    }

    @ExceptionHandler(BookingNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleBookingNotFound(
            BookingNotFoundException exception,
            HttpServletRequest request
    ) {
        return error(request, HttpStatus.NOT_FOUND,
                "BOOKING_NOT_FOUND", "Booking was not found");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRequest(
            IllegalArgumentException exception,
            HttpServletRequest request
    ) {
        return error(request, HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST", "Booking request is invalid");
    }

    private ResponseEntity<ErrorResponse> error(
            HttpServletRequest request,
            HttpStatus status,
            String code,
            String message
    ) {
        return ResponseEntity.status(status).body(
                ErrorResponse.of(code, message, RequestIdFilter.get(request)));
    }
}
