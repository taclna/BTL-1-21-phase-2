# ADR-002: Kiểm soát đồng thời khi đặt ghế

- Trạng thái: Proposed (chốt sau khi có số liệu Pha 2)
- Ngày: 2026-11-15
- Liên quan: [SPEC mục 9, 17](../../phase-1/SPEC.md), [contract Theater–Booking](../../phase-1/theater_booking_contract.md), [kế hoạch E1](../e1-tranh-ghe.md), [kế hoạch Pha 2](../ke-hoach.md)

## Bối cảnh

`BookingService.createBooking` hiện chống đặt trùng bằng ba lớp:

1. `lockShowtimeForBooking` → `ShowtimeJpaRepository.findByIdForUpdate` (`PESSIMISTIC_WRITE`, tức `SELECT ... FOR UPDATE`) trên **hàng suất chiếu**.
2. `existsByShowtimeIdAndSeatId` cho từng ghế, trả lỗi nếu ghế đã có người đặt.
3. Unique constraint `uq_booking_seat_per_showtime (showtime_id, seat_id)` ở PostgreSQL. Bước 0 của Pha 2 thêm hủy mềm, nên constraint này được thay bằng partial unique index `uq_active_showtime_seat ... WHERE released_at IS NULL` (SPEC 5.5). Ghế đã hủy được đặt lại, lịch sử vẫn giữ.

Lớp 1 cũng dùng để `ShowtimeService.cancel` không thể hủy suất trong lúc một booking đang commit.

Khóa đặt ở mức suất chiếu nên **mọi booking của cùng một suất chạy tuần tự**, kể cả khi hai khách chọn ghế khác nhau. Pha 1 chưa thấy tác động: ở 50 VU chỉ có khoảng 47 POST booking/giây và p95 POST khoảng 23 ms. Với một suất "hot" (phim mới công chiếu, mở bán đồng loạt), số giao dịch xử lý được trên một suất bị chặn bởi `1 / thời gian giữ khóa`. Các request chờ khóa vẫn chiếm connection Hikari (mặc định 10), nên có thể kéo chậm cả request đọc.

Pha 1 cũng chưa chạy kịch bản contention, nên chưa có bằng chứng đo cho tính đúng dưới tranh chấp.

## Các phương án

| | Phương án | Cách làm | Ưu | Nhược |
|---|---|---|---|---|
| **A** | Khóa ghi suất chiếu (hiện tại) | `FOR UPDATE` hàng `showtimes` → kiểm tra ghế → insert | Đơn giản, dễ chứng minh đúng, đã có test với hủy suất | Tuần tự hóa mọi booking của một suất |
| **B** | Khóa chia sẻ suất + unique constraint | Booking lấy `FOR SHARE` trên suất; insert ghế theo thứ tự `seat_id`; vi phạm unique → `409 SEAT_ALREADY_BOOKED`. Hủy suất vẫn dùng `FOR UPDATE` | Booking khác ghế chạy song song; vẫn chặn race với hủy suất | Tranh ghế được phát hiện bằng exception lúc flush; transaction thua phải rollback; cần sắp thứ tự insert để tránh deadlock |
| **C** | B + khóa theo ghế | Như B, thêm `pg_advisory_xact_lock` theo `(showtime_id, seat_id)` theo thứ tự tăng dần, rồi kiểm tra ghế trước khi insert | Song song theo ghế; người thua nhận 409 "sạch", không dựa vào exception | Thêm SQL đặc thù PostgreSQL; hash trùng chỉ làm tuần tự hóa thêm, không sai |
| D | `SERIALIZABLE` + retry | Không khóa tường minh, PostgreSQL SSI phát hiện xung đột, ứng dụng thử lại | Không cần suy luận về khóa | Lỗi `40001` phải retry; khó dự đoán latency; tùy chọn, chỉ làm nếu còn thời gian |

Phương án không xét: Redis lock hoặc hàng đợi. SPEC 3.3 loại khỏi phạm vi; thêm một hệ thống phụ không tương xứng với bài toán một database.

## Quyết định (tạm thời)

Giữ **A** làm baseline. Triển khai **B** và **C** trên nhánh riêng, đo theo [kế hoạch E1](../e1-tranh-ghe.md) trên cùng môi trường Kaggle.

Quy tắc chọn sau khi đo:

1. Phương án nào làm sai tính đúng (double-booking, thắng ≠ 1 trong contention, đặt được vào suất đã hủy) bị loại ngay.
2. Nếu B/C không cải thiện throughput hoặc p95 POST ở suất hot vượt độ dao động giữa các lượt của A, **giữ A** vì đơn giản hơn.
3. Nếu cải thiện rõ, chọn phương án đơn giản hơn trong số đạt yêu cầu (ưu tiên B hơn C).

Cập nhật trạng thái ADR thành Accepted hoặc Rejected kèm số liệu sau khi đo.

## Ràng buộc chung cho mọi phương án

- Partial unique index `uq_active_showtime_seat` luôn giữ, là lớp bảo vệ cuối. Phương án A được đo trên schema đã có hủy mềm, nên A, B, C dùng cùng một schema.
- Vi phạm index này phải được tầng `data` chuyển thành lỗi nghiệp vụ `SeatAlreadyBookedException` và trả `409 SEAT_ALREADY_BOOKED` dạng JSON envelope (SPEC mục 9 bước 7–8).
- Không đổi chữ ký `ShowtimeModuleApi` theo hướng để Booking đọc bảng Theater. Nếu B/C cần khóa chia sẻ, Theater bổ sung method `lockShowtimeForBookingShared` (hoặc đổi implementation của method hiện có), Booking vẫn chỉ gọi qua interface.
- Integration test "đặt vé đồng thời với hủy suất" phải tiếp tục pass.

## Hệ quả

- Cần sửa trước các lệch contract Pha 1 (400 dạng chuỗi → 409 JSON) thì mới đo contention được.
- Nếu chọn B hoặc C: thêm tài liệu về thứ tự khóa/insert và test deadlock với booking nhiều ghế chồng nhau.
- Nếu giữ A: ghi rõ giới hạn throughput mỗi suất đo được trong README như một giới hạn đã biết.
