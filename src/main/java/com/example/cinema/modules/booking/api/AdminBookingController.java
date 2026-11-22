package com.example.cinema.modules.booking.api;

import com.example.cinema.modules.booking.business.BookingService;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/bookings")
@ConditionalOnProperty(name = "cinema.booking.enabled", havingValue = "true", matchIfMissing = true)
public class AdminBookingController {

    private final BookingService bookingService;

    public AdminBookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @GetMapping
    public ResponseEntity<BookingPageResponse> list(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize
    ) {
        var bookingPage = bookingService.getAllBookings(page, pageSize);
        List<BookingSummaryResponse> items = bookingPage.items().stream()
                .map(BookingSummaryResponse::from)
                .toList();
        long totalPages = (bookingPage.totalItems() + pageSize - 1) / pageSize;
        return ResponseEntity.ok(new BookingPageResponse(
                items,
                new BookingPaginationMeta(page, pageSize, bookingPage.totalItems(), totalPages)));
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<BookingDataResponse<BookingDetailResponse>> getById(
            @PathVariable UUID bookingId
    ) {
        return ResponseEntity.ok(new BookingDataResponse<>(
                BookingDetailResponse.from(bookingService.getBookingById(bookingId))));
    }

    @DeleteMapping("/{bookingId}")
    public ResponseEntity<Void> cancel(@PathVariable UUID bookingId) {
        bookingService.cancelBookingByAdmin(bookingId);
        return ResponseEntity.noContent().build();
    }
}
