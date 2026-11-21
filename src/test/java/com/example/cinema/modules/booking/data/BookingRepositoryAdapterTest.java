package com.example.cinema.modules.booking.data;

import com.example.cinema.modules.booking.business.Booking;
import com.example.cinema.modules.booking.business.SeatAlreadyBookedException;
import com.example.cinema.modules.booking.business.bookingSeat;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BookingRepositoryAdapterTest {

    private final BookingJpaRepository bookingJpaRepository = mock(BookingJpaRepository.class);
    private final BookingSeatJpaRepository bookingSeatJpaRepository = mock(BookingSeatJpaRepository.class);
    private final BookingRepositoryAdapter adapter = new BookingRepositoryAdapter(
            bookingJpaRepository, bookingSeatJpaRepository);

    @Test
    void mapsOnlyActiveSeatUniqueViolationToBusinessConflict() {
        DataIntegrityViolationException failure = constraintFailure("uq_active_showtime_seat");
        when(bookingSeatJpaRepository.saveAllAndFlush(anyList())).thenThrow(failure);

        assertThatThrownBy(() -> adapter.save(booking()))
                .isInstanceOf(SeatAlreadyBookedException.class);
    }

    @Test
    void preservesUnrelatedDataIntegrityViolation() {
        DataIntegrityViolationException failure = constraintFailure("fk_booking_user");
        when(bookingSeatJpaRepository.saveAllAndFlush(anyList())).thenThrow(failure);

        assertThatThrownBy(() -> adapter.save(booking()))
                .isSameAs(failure);
    }

    private static DataIntegrityViolationException constraintFailure(String constraintName) {
        ConstraintViolationException violation = mock(ConstraintViolationException.class);
        when(violation.getConstraintName()).thenReturn(constraintName);
        return new DataIntegrityViolationException(
                "constraint violation", new SQLException("duplicate", "23505", violation));
    }

    private static Booking booking() {
        return new Booking(
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(new bookingSeat(UUID.randomUUID(), new BigDecimal("90000.00"))),
                new BigDecimal("90000.00"));
    }
}
