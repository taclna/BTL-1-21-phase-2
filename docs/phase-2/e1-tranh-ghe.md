# E1: tranh chấp ghế và kiểm soát đồng thời

> Ngày lập: 15/11/2026 · Trạng thái: đã duyệt, thuộc [kế hoạch Pha 2](ke-hoach.md)\
> Phân công và lịch: [work_plan.md](work_plan.md)\
> Quyết định kiến trúc đi kèm: [ADR-002](adr/002-kiem-soat-tranh-ghe.md)\
> Baseline so sánh: commit `36c8922` (Kaggle CPU, 28–29/10/2026), xem [README mục 7](../../README.md)

## 1. Vì sao chọn hướng này

SPEC mục 17 yêu cầu chỉ cải tiến khi đo được vấn đề. Baseline Pha 1 cho thấy:

- Đọc phim, lịch chiếu và ghế rất nhanh: p95 dưới 10 ms ở 50 VU, chưa thấy dấu hiệu bão hòa. Thêm cache lúc này không có gì để chứng minh.
- **Kịch bản contention chưa chạy**, nên đây là khoảng trống bằng chứng duy nhất của Pha 1 về tính đúng.
- Code hiện khóa `FOR UPDATE` trên **cả hàng suất chiếu** ([ShowtimeJpaRepository.java](../../src/main/java/com/example/cinema/modules/theater/data/ShowtimeJpaRepository.java)). `booking.js` dồn mọi booking vào một suất duy nhất, nên các POST đang chạy tuần tự. Ở 50 VU mới có khoảng 47 POST/giây nên chưa lộ ra.

Thuộc tính chất lượng nghiên cứu: **consistency/reliability** (không bán trùng ghế) và **performance/scalability** (throughput đặt vé trên một suất hot). Đúng dòng "Tranh chấp ghế cao" trong bảng SPEC mục 17.

## 2. Câu hỏi và giả thuyết

| # | Giả thuyết | Cách kiểm chứng |
|---|---|---|
| H0 | Mọi phương án đều giữ đúng: mỗi lượt contention có đúng 1 booking thắng, N−1 nhận `409 SEAT_ALREADY_BOOKED`, DB không có ghế giữ trùng | `contention.js` + `verify-contention.sql` |
| H1 | Với phương án A, throughput booking trên một suất bị chặn quanh `1 / thời gian giữ khóa`. Khi tốc độ đến gần ngưỡng này, p95 POST tăng vọt | `hot-showtime.js` với tốc độ đến tăng dần |
| H2 | B và C có throughput mỗi suất cao hơn A rõ rệt cho đến khi chạm giới hạn CPU hoặc connection pool | Cùng kịch bản, so sánh A/B/C |
| H3 | Với A, khi booking xếp hàng chờ khóa, chúng giữ connection Hikari (10) và làm tăng p95 của GET trong tải hỗn hợp | `booking.js` + lấy mẫu số connection đang chờ khóa |
| H4 | Booking nhiều ghế chồng nhau không gây deadlock nếu insert/khóa theo thứ tự `seat_id` | `overlap.js` + đếm `pg_stat_database.deadlocks` |

Ước lượng để chọn mức tải, chưa phải kết quả: nếu một transaction giữ khóa khoảng 3–5 ms thì A bão hòa quanh 200–300 booking/giây/suất.

## 3. Bước 0: hoàn thiện Pha 1 theo SPEC

Phải làm xong và merge trước mọi phép đo. Gồm hai nhóm, đã đối chiếu với code ngày 15/11.

**Sửa lệch contract** (từ [huong-dan-kaggle-cpu.md](../phase-1/huong-dan-kaggle-cpu.md) mục 1):

| # | Vấn đề hiện tại | Việc cần làm | File |
|---|---|---|---|
| 0.1 | Ghế đã đặt ném `IllegalArgumentException` → 400 dạng chuỗi | Tạo `SeatAlreadyBookedException` ở business; ánh xạ `409` với `error.code=SEAT_ALREADY_BOOKED` | `BookingService`, `BookingExceptionHandler` |
| 0.2 | Vi phạm unique constraint rơi vào handler `Exception` → 500 | Adapter dùng `saveAllAndFlush`, bắt `DataIntegrityViolationException` của index ghế (sau 0.7 là `uq_active_showtime_seat`) và chuyển thành `SeatAlreadyBookedException` | `BookingRepositoryAdapter` |
| 0.3 | Lỗi Booking 400/404 trả chuỗi thô | Dùng error envelope chung (`code`, `message`, `requestId`) | `BookingExceptionHandler` |
| 0.4 | `contention.js` đọc `body.data.bookingId` nhưng API trả `bookingId` ở ngoài cùng | Bọc response thành công của Booking trong `{data: ...}` theo SPEC 7.1; cập nhật `booking.js`, `contention.js`, stub `load-test/tests/client-check.mjs` và test | `BookingController`, `load-test/` |
| 0.5 | `verify-contention.sql` dùng `released_at`, `bookings.status` (chưa tồn tại) | Giữ nguyên ý nghĩa SQL; sau 0.7 schema đã có hai cột này nên chỉ cần chạy thử và sửa chi tiết | `verify-contention.sql` |
| 0.6 | Chưa có test hai khách cùng đặt một ghế | Integration test Testcontainers: 2 thread, cùng ghế → đúng một 201 và một 409 | `src/test/...` |

**Bổ sung nghiệp vụ SPEC còn thiếu** (SPEC 5.5, 6.11–6.13, 7.5):

| # | Việc cần làm | Chi tiết |
|---|---|---|
| 0.7 | Hủy mềm booking | Migration mới thêm `bookings.status` (`CONFIRMED`/`CANCELLED`), `cancelled_at`, `booking_seats.released_at`. Bỏ unique `uq_booking_seat_per_showtime`, thay bằng partial unique index `uq_active_showtime_seat ON booking_seats (showtime_id, seat_id) WHERE released_at IS NULL`. Hủy là đổi status và ghi `released_at` trong cùng transaction, không xóa bản ghi. Các truy vấn ghế đã đặt, kiểm tra ghế trống và `existsConfirmedByShowtimeId` lọc `CONFIRMED` và `released_at IS NULL` |
| 0.8 | Quy tắc hủy trước 30 phút | Chỉ hủy booking `CONFIRMED` khi giờ chiếu còn ít nhất 30 phút (lấy giờ chiếu qua `ShowtimeModuleApi`, so bằng `Clock`). Vi phạm trả `409 BOOKING_CANNOT_BE_CANCELLED`; hủy lại booking đã hủy cũng trả lỗi này |
| 0.9 | Mã booking | Cột `booking_code varchar(20)` unique, khó đoán: sinh bằng `SecureRandom`, 10 ký tự Base32 (khoảng 50 bit), thử lại khi trùng. Trả trong response chi tiết và danh sách |
| 0.10 | Admin xem mọi booking | `GET /api/v1/admin/bookings?page&pageSize` có phân trang, sắp theo `created_at desc`. Admin xem được chi tiết và hủy được mọi booking qua `GET`/`DELETE /api/v1/bookings/{id}` (SPEC 7.5: Owner/Admin); Customer vẫn chỉ thấy booking của mình |

| 0.11 | Phân trang `GET /api/v1/bookings` | `page`/`pageSize` (mặc định 1/20, tối đa 100), response `{data: [...], meta: {...}}` theo SPEC 7.1 và 7.5, sắp theo `created_at desc`. Cập nhật `booking.js` vì script đọc danh sách này |

Với booking đã tạo trước migration: đặt `status = CONFIRMED` và sinh `booking_code` ngay trong migration. `payment_status` giữ nguyên, không liên quan tới trạng thái giữ ghế.

Sau bước 0, gắn tag **`phase-1-baseline`** (tag này hiện chưa có trong repo). Đây là Pha 1 hoàn chỉnh theo SPEC và là phương án **A**. Số liệu Kaggle 28/10 đo trên commit `36c8922`, trước khi đổi schema, nên chỉ dùng tham khảo. Mọi so sánh của Pha 2 dùng A đo lại trong cùng phiên.

## 4. Các phương án cần đo

Chi tiết và đánh đổi xem [ADR-002](adr/002-kiem-soat-tranh-ghe.md). Mỗi phương án là một nhánh và một tag riêng, chỉ khác nhau ở cơ chế khóa:

| Tag | Phương án | Thay đổi so với A |
|---|---|---|
| `phase-1-baseline` | **A**: `FOR UPDATE` suất chiếu | — |
| `phase-2-share-lock` | **B**: `FOR SHARE` suất + unique constraint | Theater thêm method khóa chia sẻ; Booking bỏ vòng `existsBy...`, insert theo thứ tự `seat_id`, dựa vào 0.2 để trả 409 |
| `phase-2-seat-lock` | **C**: B + advisory lock từng ghế | Adapter gọi `pg_advisory_xact_lock(hashtextextended(showtime_id::text \|\| seat_id::text, 0))` theo thứ tự tăng dần trước khi kiểm tra và insert |
| (tùy chọn) | **D**: `SERIALIZABLE` + retry | Chỉ làm nếu còn thời gian |

Lý do dùng tag thay cho cờ cấu hình: giữ code đơn giản (SPEC mục 22) và tách rõ "một nhóm yếu tố thay đổi" theo quy trình SPEC mục 17.

Mỗi phương án phải pass toàn bộ `mvn verify` trước khi đo, gồm test đặt vé đồng thời với hủy suất và test 0.6.

## 5. Kịch bản tải

Giữ nguyên môi trường Pha 1: Kaggle CPU 4 vCPU, Java 21, cùng phiên bản PostgreSQL, JVM `-Xms256m -Xmx768m`, Hikari mặc định, API, DB và k6 trong cùng phiên, think time 100 ms.

| Kịch bản | Mục đích | Mô hình tải | Mức tải | Lặp |
|---|---|---|---|---|
| S1 `contention.js` (có sẵn) | H0: tính đúng | 1 request/VU, cùng 1 ghế | 10 / 25 / 50 VU | 3 lượt/mức, mỗi lượt một ghế mới (index 1–9) |
| S2 `hot-showtime.js` (mới) | H1, H2: throughput một suất | Open model `constant-arrival-rate`; mỗi iteration là POST 1 ghế riêng của VU rồi DELETE | 25 / 50 / 100 / 200 / 400 booking/giây; warm-up 30 giây + đo 120 giây | 3 lượt/mức |
| S3 `booking.js` (có sẵn) | H3: hồi quy tải hỗn hợp | Như Pha 1 | 10 / 25 / 50 VU, thêm 100 VU | 3 lượt/mức |
| S4 `overlap.js` (mới) | H4: deadlock | Booking 2–4 ghế, các VU chọn tập ghế chồng nhau theo thứ tự ngẫu nhiên | 25 / 50 VU, 60 giây | 3 lượt |

Ghi chú cho kịch bản mới:

- **S2 dùng open model** để thấy rõ hàng đợi. Với closed model, VU tự chậm lại khi API chậm, làm che mất điểm bão hòa. Báo cáo cả `dropped_iterations` của k6.
- S2 cần phòng lớn hơn: khoảng 400 ghế và 400 user. `seed.mjs` hiện tạo `2·VUS+9` ghế, nên cần thêm `SEED_SCOPE=hot`.
- Gắn tag riêng cho request DELETE (`op=cancel`) để tách latency POST khỏi request dọn dữ liệu.

## 6. Số liệu cần thu

| Nhóm | Chỉ số | Nguồn |
|---|---|---|
| Tính đúng | Số thắng/409 mỗi lượt contention; số ghế giữ trùng (phải = 0) | k6 counter, `verify-contention.sql` |
| Hiệu năng | Throughput đạt được so với mục tiêu, POST p50/p95/p99, GET p95 (S3), `dropped_iterations` | k6 summary, chỉ pha `measure` |
| Lỗi | Tỷ lệ 5xx, 409 ngoài dự kiến, deadlock (`pg_stat_database.deadlocks` trước/sau), lỗi `40001` nếu làm D | k6, SQL |
| Khóa | Số backend đang chờ khóa, lấy mẫu mỗi giây: `SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'` | Thêm vào `run-baseline.py` |
| Tài nguyên | CPU/RSS của API, DB, k6 | `run-baseline.py` (có sẵn) |
| Pool (nên có) | `hikaricp.connections.active/pending` | Cần mở actuator metrics ở profile đo, phối hợp Người 1 |

Tổng hợp theo SPEC mục 17: median của 3 lượt kèm khoảng min–max. Không gộp p95 của các lượt khác nhau.

## 7. Tiêu chí kết luận

1. Phương án nào vi phạm H0 bị loại, không cần xét hiệu năng.
2. Một cải thiện chỉ được tính khi chênh lệch median **lớn hơn khoảng min–max** của cả hai phương án ở cùng mức tải.
3. Nếu B/C không cải thiện rõ ở S2 thì giữ A và ghi giới hạn throughput mỗi suất vào README.
4. Nếu cải thiện, chọn phương án đơn giản hơn trong số đạt yêu cầu (B trước C), cập nhật ADR-002 thành Accepted kèm bảng số liệu, merge vào `main`.
5. Báo cáo cả trade-off: độ phức tạp code, phụ thuộc vào tính năng riêng của PostgreSQL, tác động lên S3.

## 8. Phân công và lịch

Xem [work_plan.md](work_plan.md). Tóm tắt phần của E1:

| Người | Việc |
|---|---|
| Người 1 | Hoàn thiện 0.1–0.8, ưu tiên 0.7 hủy mềm và partial index để đạt CP1a (Người 4 review); lấy mẫu `pg_stat_activity` và đếm deadlock trong `run-baseline.py`; metrics Hikari; quản lý tag |
| Người 2 | Thêm `Idempotency-Key` vào script Phase 2; viết `hot-showtime.js`, `SEED_SCOPE=hot`; chạy đo trên Kaggle; hỗ trợ rà script 0.4–0.5 sau khi contract được merge |
| Người 3 | Method khóa chia sẻ trong `ShowtimeModuleApi`; `overlap.js`; giữ test hủy suất đồng thời |
| Người 4 | Hoàn thiện 0.9 mã booking, 0.10 Admin xem mọi booking và 0.11 phân trang `GET /bookings` sau CP1a (Người 1 review); triển khai B (tuần 2) và C (tuần 3); test deadlock với booking nhiều ghế |

Mốc của E1: tag `phase-1-baseline` chậm nhất thứ Ba 24/11; chạy thử A trên Kaggle ở tuần 2; đóng băng các tag B/C cuối tuần 3; đo chính thức ở tuần 4 (2 phiên, thứ tự A→B→C và C→B→A); chốt ADR-002 ở tuần 5.

## 9. Rủi ro

| Rủi ro | Cách giảm |
|---|---|
| Phiên Kaggle khác nhau cho kết quả lệch nhau | Chạy A và B/C trong cùng một phiên nếu kịp thời gian; luôn ghi CPU, quota và phiên bản |
| k6 chiếm CPU chung với API ở mức 400 booking/giây | Theo dõi CPU của k6; nếu k6 trở thành nút thắt thì ghi nhận và dừng ở mức thấp hơn |
| Token JWT hết hạn giữa các kịch bản dài | Chạy `refresh-tokens.mjs` giữa các kịch bản |
| Hủy mềm làm `booking_seats` tăng liên tục trong S2 (mỗi iteration để lại một bản ghi đã giải phóng), latency có thể trôi dần trong một lượt | Mỗi phương án dùng database mới; ghi số dòng `booking_seats` đầu và cuối mỗi lượt; so p95 nửa đầu và nửa sau pha đo |
| Ghế bị kẹt khi DELETE lỗi trong S2 | Đếm riêng lỗi DELETE; dọn dữ liệu bằng `cleanup.mjs` giữa các lượt |
| B/C phát sinh deadlock khó tái hiện | S4 cố ý tạo chồng ghế; kiểm tra bộ đếm deadlock mỗi lượt |
