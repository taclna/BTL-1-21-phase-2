package com.example.cinema.unit.booking;

import com.example.cinema.modules.booking.api.BookingController;
import com.example.cinema.modules.booking.api.CreateBookingRequest;
import com.example.cinema.modules.booking.business.Booking;
import com.example.cinema.modules.booking.business.BookingService;
import com.example.cinema.modules.booking.business.BookingPage;
import com.example.cinema.modules.booking.business.bookingSeat;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BookingControllerTest {

    private final BookingService bookingService = mock(BookingService.class);
    private final BookingController controller = new BookingController(bookingService);
    private final UUID userId = UUID.randomUUID();
    private final UUID showtimeId = UUID.randomUUID();
    private final UUID seatId = UUID.randomUUID();
    private final Booking booking = new Booking(
            userId,
            showtimeId,
            List.of(new bookingSeat(seatId, new BigDecimal("90000.00"))),
            new BigDecimal("90000.00"));

    @Test
    void createWrapsBookingInDataEnvelope() {
        CreateBookingRequest request = new CreateBookingRequest();
        request.setShowtimeId(showtimeId);
        request.setSeatIds(List.of(seatId));
        when(bookingService.createBooking(userId, showtimeId, List.of(seatId))).thenReturn(booking);

        var response = controller.create(request, userId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data().getBookingId()).isEqualTo(booking.getBookingId());
    }

    @Test
    void detailWrapsBookingInDataEnvelope() {
        when(bookingService.getBookingByUserId(userId, booking.getBookingId())).thenReturn(booking);

        var response = controller.getByUserIdAndBookingId(booking.getBookingId(), userId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data().getBookingId()).isEqualTo(booking.getBookingId());
    }

    @Test
    void listWrapsBookingsInDataEnvelope() {
        when(bookingService.getBookingsByUserId(userId, 1, 20))
                .thenReturn(new BookingPage(List.of(booking), 1));

        var response = controller.getAllByUserId(userId, 1, 20);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).hasSize(1);
        assertThat(response.getBody().data().getFirst().getBookingId()).isEqualTo(booking.getBookingId());
    }
}
