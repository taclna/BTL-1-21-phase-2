package com.example.cinema.modules.booking.api;

import com.example.cinema.modules.booking.business.BookingService;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bookings")
@ConditionalOnProperty(name = "cinema.booking.enabled", havingValue = "true", matchIfMissing = true)
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    public ResponseEntity<BookingDataResponse<BookingDetailResponse>> create(
            @RequestBody CreateBookingRequest request,
            @AuthenticationPrincipal UUID userId
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(new BookingDataResponse<>(BookingDetailResponse.from(
                bookingService.createBooking(userId, request.getShowtimeId(), request.getSeatIds()))));
    }

    @GetMapping
    public ResponseEntity<BookingPageResponse> getAllByUserId(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize
    ) {
        var bookingPage = bookingService.getBookingsByUserId(userId, page, pageSize);
        List<BookingSummaryResponse> items = bookingPage.items().stream()
                .map(BookingSummaryResponse::from)
                .toList();
        long totalPages = (bookingPage.totalItems() + pageSize - 1) / pageSize;
        return ResponseEntity.ok(new BookingPageResponse(
                items,
                new BookingPaginationMeta(page, pageSize, bookingPage.totalItems(), totalPages)));
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<BookingDataResponse<BookingDetailResponse>> getByUserIdAndBookingId(
            @PathVariable UUID bookingId,
            @AuthenticationPrincipal UUID userId
    ) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"))) {
            return ResponseEntity.ok(new BookingDataResponse<>(BookingDetailResponse.from(
                    bookingService.getBookingById(bookingId))));
        }
        return ResponseEntity.ok(new BookingDataResponse<>(BookingDetailResponse.from(
                bookingService.getBookingByUserId(userId, bookingId))));
    }

    @DeleteMapping("/{bookingId}")
    public ResponseEntity<Void> cancel(
            @PathVariable UUID bookingId,
            @AuthenticationPrincipal UUID userId
    ) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"))) {
            bookingService.cancelBookingByAdmin(bookingId);
            return ResponseEntity.noContent().build();
        }
        bookingService.cancelBooking(userId, bookingId);
        return ResponseEntity.noContent().build();
    }
}
