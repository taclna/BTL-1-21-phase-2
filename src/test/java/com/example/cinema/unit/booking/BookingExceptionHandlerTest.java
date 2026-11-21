package com.example.cinema.unit.booking;

import com.example.cinema.modules.booking.api.BookingExceptionHandler;
import com.example.cinema.modules.booking.business.BookingCannotBeCancelledException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class BookingExceptionHandlerTest {

    @Test
    void cancellationRuleViolationUsesDocumentedConflictCode() {
        var response = new BookingExceptionHandler().handleBookingCannotBeCancelled(
                new BookingCannotBeCancelledException(UUID.randomUUID()),
                new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("BOOKING_CANNOT_BE_CANCELLED");
    }
}
