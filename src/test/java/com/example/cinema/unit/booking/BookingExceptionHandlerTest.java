package com.example.cinema.unit.booking;

import com.example.cinema.modules.booking.api.BookingExceptionHandler;
import com.example.cinema.modules.booking.business.BookingCannotBeCancelledException;
import com.example.cinema.modules.booking.business.BookingNotFoundException;
import com.example.cinema.modules.booking.business.SeatAlreadyBookedException;
import com.example.cinema.shared.api.RequestIdFilter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class BookingExceptionHandlerTest {

    private final BookingExceptionHandler handler = new BookingExceptionHandler();

    @Test
    void cancellationRuleViolationUsesDocumentedConflictCode() {
        var response = handler.handleBookingCannotBeCancelled(
                new BookingCannotBeCancelledException(UUID.randomUUID()),
                new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("BOOKING_CANNOT_BE_CANCELLED");
    }

    @Test
    void seatConflictUsesDocumentedEnvelope() throws Exception {
        String requestId = UUID.randomUUID().toString();
        var response = handler.handleSeatAlreadyBooked(requestWithId(requestId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("SEAT_ALREADY_BOOKED");
        assertThat(response.getBody().error().message()).isEqualTo("Seat has already been booked");
        assertThat(response.getBody().error().requestId()).isEqualTo(requestId);
    }

    @Test
    void bookingNotFoundUsesDocumentedEnvelope() {
        var response = handler.handleBookingNotFound(
                new BookingNotFoundException(UUID.randomUUID()),
                new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("BOOKING_NOT_FOUND");
    }

    @Test
    void invalidBookingUsesCommonErrorEnvelope() {
        var response = handler.handleInvalidRequest(
                new IllegalArgumentException("internal validation detail"),
                new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("MALFORMED_REQUEST");
        assertThat(response.getBody().error().message()).isEqualTo("Booking request is invalid");
    }

    private static MockHttpServletRequest requestWithId(String requestId) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, requestId);
        new RequestIdFilter().doFilter(
                request,
                new MockHttpServletResponse(),
                new MockFilterChain());
        return request;
    }
}
