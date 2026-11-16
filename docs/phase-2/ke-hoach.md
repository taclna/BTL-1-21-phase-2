# Kế hoạch Pha 2

> Ngày lập: 15/11/2026 · Trạng thái: đã duyệt\
> Thời gian: 16/11 – 20/12/2026 (5 tuần), 4 người\
> Phân công và lịch: [work_plan.md](work_plan.md)\
> Baseline so sánh: tag `phase-1-baseline` (Pha 1 hoàn chỉnh theo SPEC, xem mục 3), đo lại trong mỗi phiên.\
> Số liệu tham khảo: commit `36c8922`, Kaggle CPU 28–29/10/2026 ([README mục 7](../../README.md), [số liệu](../phase-1/performance/))

## 1. Mục tiêu

Pha 1 đã có hệ thống chạy đúng và một baseline đo ở mức 10/25/50 VU. Baseline đó **chưa tìm ra điểm bão hòa** và **chưa đo tranh ghế**. Pha 2 làm hai việc:

1. Tạo số liệu mà Pha 1 chưa có: đẩy tải đến khi hệ thống chậm, đo tranh ghế, đo trên dataset lớn.
2. Thử 6 cải tiến (E1–E6). Mỗi cải tiến được so với baseline bằng số liệu, rồi kết luận giữ hay bỏ kèm trade-off.

Nguyên tắc giữ theo [SPEC mục 17](../phase-1/SPEC.md): mỗi lần chỉ thay một nhóm yếu tố, chạy lại cùng cấu hình, so sánh cả hiệu năng, tài nguyên và tính đúng.

## 2. Các thí nghiệm

| # | Thí nghiệm | Thuộc tính chất lượng | Phương án so sánh | ADR | Ưu tiên |
|---|---|---|---|---|---|
| E1 | [Tranh ghế](e1-tranh-ghe.md) | Consistency, performance | A khóa cả suất / B khóa chia sẻ / C khóa từng ghế | [002](adr/002-kiem-soat-tranh-ghe.md) | Bắt buộc |
| E4 | Tối ưu query / index | Performance | Hiện tại / đã tối ưu, trên dataset lớn | — | Bắt buộc |
| E5 | Observability | Observability | Tắt / bật metrics Prometheus | — | Bắt buộc |
| E2 | Cache đọc | Performance, scalability | Không cache / Caffeine | [003](adr/003-cache-doc.md) | Nên có |
| E3 | Rate limiting / backpressure | Availability | Không giới hạn / giới hạn số request đồng thời | [004](adr/004-rate-limiting.md) | Nên có |
| E6 | Nhiều replica | Scalability, availability | 1 / 2 instance qua nginx; cache Caffeine riêng / Redis chung | [005](adr/005-mo-rong-ngang.md) | Có thể bỏ |

Cột "Ưu tiên" là thứ tự cắt khi bị trễ, xem [work_plan.md mục 5](work_plan.md#5-ưu-tiên-và-điểm-cắt).

## 3. Bước 0: hoàn thiện Pha 1 theo SPEC

Trước mọi phép đo phải làm xong hai nhóm việc, chi tiết ở [E1 mục 3](e1-tranh-ghe.md#3-bước-0-hoàn-thiện-pha-1-theo-spec):

1. **Sửa lệch contract:** 409 `SEAT_ALREADY_BOOKED`, error envelope của Booking, bọc response Booking trong `{data: ...}` (SPEC 7.1), sửa script contention, test hai khách cùng ghế.
2. **Bổ sung nghiệp vụ còn thiếu:**
   - Hủy mềm booking: trạng thái `CONFIRMED`/`CANCELLED`, `released_at`, partial unique index thay unique constraint.
   - Chỉ cho hủy trước giờ chiếu ít nhất 30 phút.
   - Mã booking khó đoán.
   - Admin xem mọi booking: `GET /api/v1/admin/bookings`, xem chi tiết và hủy mọi booking.
   - `GET /api/v1/bookings` có `page`/`pageSize` và response phân trang đúng SPEC 7.5.

Sau đó gắn tag **`phase-1-baseline`**, chậm nhất thứ Ba 24/11. Đây là phương án A, và mọi thí nghiệm so với tag này. Số liệu 28/10 đo trước khi đổi schema nên chỉ dùng tham khảo, không so trực tiếp.

Các phần việc ở bước 0 đều phải có test và mô tả OpenAPI như một feature bình thường (SPEC 20.2). Sau đó cập nhật `docs/phase-1/openapi.json`, [so-do-luong-nghiep-vu.md](../phase-1/so-do-luong-nghiep-vu.md) mục 5 và 7 (luồng hủy) và README.

## 4. Môi trường và quy trình đo chung

- **Môi trường:** Kaggle CPU 4 vCPU, Java 21, PostgreSQL theo gói của phiên (ghi phiên bản thực tế), JVM `-Xms256m -Xmx768m`, Hikari mặc định 10. API, DB và k6 chạy cùng phiên. Riêng E6 có thêm nginx, Redis và ghim CPU bằng `taskset`.
- **Lặp:** 3 lượt mỗi mức; báo median kèm khoảng min–max. Không gộp p95 của các lượt khác nhau.
- **So sánh công bằng:** các phương án của một thí nghiệm chạy **trong cùng một phiên Kaggle**, dùng database mới cho mỗi phương án.
- **Tag:** mỗi phương án có code riêng là một tag (`phase-2-share-lock`, `phase-2-seat-lock`, `phase-2-cache`, `phase-2-ratelimit`, `phase-2-query`). Phương án chỉ khác cấu hình chạy trên cùng tag, ghi rõ cấu hình trong `environment.json`.
- **Bằng chứng:** CSV tổng hợp và evidence JSON lưu ở `docs/phase-2/performance/`. Raw k6 đóng gói ZIP, không commit fixture có JWT.
- **Định nghĩa "throughput bền vững tối đa":** mức tốc độ đến cao nhất mà p95 GET < 500 ms, p95 POST booking < 1000 ms, lỗi kỹ thuật < 1% và `dropped_iterations` < 1%.

### Kịch bản tải

| Mã | Script | Dùng cho | Mô hình |
|---|---|---|---|
| S1 | `contention.js` (có sẵn) | E1, E6 | 1 request/VU, cùng 1 ghế |
| S2 | `hot-showtime.js` (mới) | E1 | `constant-arrival-rate`, POST + DELETE trên một suất |
| S3 | `booking.js` (có sẵn) | E1, E5, so sánh cấu hình cuối | Tải hỗn hợp theo SPEC 16, closed model |
| S4 | `overlap.js` (mới) | E1 | Booking nhiều ghế chồng nhau |
| S5 | `stress-read.js` (mới) | E2, E5, E6 | `ramping-arrival-rate` theo bậc, 60 giây/bậc, tỷ trọng đọc như S3 |
| S6 | `overload.js` (mới) | E3 | Tốc độ cố định 1,5× và 2× điểm bão hòa; thêm mẫu spike: bình thường → 2× → bình thường |
| S7 | `large-dataset` (seed mới, `SEED_SCOPE=large`) | E4 | Dataset lớn, xem E4 |

## 5. E1: tranh ghế

Chi tiết ở [e1-tranh-ghe.md](e1-tranh-ghe.md) và [ADR-002](adr/002-kiem-soat-tranh-ghe.md).

## 6. E2: cache đọc

E2 chỉ được đo sau khi đã có baseline và kết quả E4. Query phải được sửa và hiểu rõ trước khi thêm cache, tránh dùng cache để che N+1 hoặc execution plan kém hiệu quả. Tag E2 vẫn tách riêng từ `phase-1-baseline` để nguyên tắc mỗi thí nghiệm chỉ thay một nhóm yếu tố không bị phá vỡ.

**Vấn đề cần đo trước:** điểm bão hòa của tải đọc với phương án A (S5, tuần 2). Nếu nút thắt nằm ở CPU của DB hoặc ở số connection pool, cache có cơ sở. Nếu nút thắt là CPU của API khi serialize JSON, cache sẽ giúp ít hơn, và ghi đúng như vậy.

**Cái gì được cache:**

| Dữ liệu | Module | Key | Xóa cache khi |
|---|---|---|---|
| Danh sách phim | movie | `(page, pageSize, status)` | Tạo, sửa, xóa phim (xóa toàn bộ cache danh sách) |
| Chi tiết phim, `findForShowtime` | movie | `movieId` | Sửa, xóa phim đó |
| Lịch chiếu theo phim và ngày | theater | `(movieId, date)` | Tạo, hủy suất của phim đó |
| Snapshot suất kèm sơ đồ ghế (`findShowtime`) | theater | `showtimeId` | Hủy suất. Sơ đồ ghế không đổi sau khi phòng có suất (SPEC 5.3) |

**Không cache:** trạng thái ghế đã đặt và dữ liệu booking, vì chúng đổi theo từng giao dịch. Endpoint ghế vẫn đọc ghế đã đặt trực tiếp từ DB, chỉ sơ đồ ghế được lấy từ cache.

**Cách làm:** Spring Cache (`spring-boot-starter-cache` + Caffeine), `@Cacheable` và `@CacheEvict` trên method của business service. Mỗi module chỉ cache và xóa cache dữ liệu của mình. TTL 60 giây làm lưới an toàn; giới hạn kích thước; bật `recordStats` để có hit ratio cho E5. Annotation `org.springframework.cache` nằm ngoài nhóm bị cấm ở tầng business, nhưng cần kiểm tra lại `ArchitectureTest`.

**Giả thuyết:** cache làm tăng throughput bền vững tối đa của S5 và giảm CPU của DB ở cùng mức tải; không làm thay đổi kết quả S1.

**Test tính đúng:** sau khi Admin PATCH hoặc DELETE phim, hoặc hủy suất, request GET tiếp theo phải thấy dữ liệu mới ngay (trên một instance).

**Đo:** S5 với A và với `phase-2-cache` trong cùng phiên; S3 ở 50 VU để xem có thay đổi ở mức tải thường không.

## 7. E3: rate limiting / backpressure

**Vấn đề cần đo trước:** hành vi của A khi tải vượt điểm bão hòa (S6, tuần 2). Kỳ vọng là latency của mọi request tăng vọt và có timeout, tức hệ thống "chậm cho tất cả".

**Cách làm (xem [ADR-004](adr/004-rate-limiting.md)):** một filter trong `config` giới hạn số request đang xử lý (semaphore). Khi vượt giới hạn, request chờ tối đa một khoảng ngắn rồi nhận `429 TOO_MANY_REQUESTS` dạng error envelope kèm `Retry-After`. Health check và actuator được miễn. Giới hạn ban đầu chọn từ điểm bão hòa đo ở tuần 2, rồi tinh chỉnh trong tuần 3. Giới hạn theo user cho `POST /auth/login` và `POST /bookings` là phần mở rộng, làm nếu còn thời gian.

**Giả thuyết:** khi quá tải, có limiter thì số request thành công mỗi giây (goodput) giữ gần mức bão hòa và p95 của request được nhận dưới ngưỡng. Không có limiter thì goodput giảm và p95 tăng vọt. Sau spike, hệ thống có limiter hồi phục nhanh hơn.

**Chỉ số:** goodput (2xx/giây), p95 của request được nhận, tỷ lệ 429, tỷ lệ 5xx và timeout, thời gian hồi phục sau spike.

**Đánh đổi cần báo cáo:** một phần request hợp lệ bị từ chối; giới hạn tính theo từng instance (E6 có 2 instance thì giới hạn tổng gấp đôi).

## 8. E4: tối ưu query / index

E4 được ưu tiên trước E2. Mục tiêu là tạo một đường truy cập database hiệu quả và đo được trên dataset lớn trước khi cache làm giảm số lần query.

**Ứng viên đã thấy trong code:**

- `GET /api/v1/bookings` bị N+1: [BookingRepositoryAdapter.toBooking](../../src/main/java/com/example/cinema/modules/booking/data/BookingRepositoryAdapter.java) chạy một query lấy ghế cho mỗi booking. Pagination là phần sửa contract bắt buộc ở Bước 0; E4 đo và sửa số query trên từng trang.
- Endpoint trạng thái ghế: nạp toàn bộ sơ đồ ghế từ Theater rồi nạp danh sách ghế đã đặt. Cần kiểm tra plan khi phòng lớn.
- Lịch chiếu theo phim và ngày: kiểm tra index `showtimes(movie_id, starts_at)` có được dùng hay không.
- `GET /api/v1/admin/bookings` (thêm ở bước 0): phân trang trên toàn bộ booking, cần index phù hợp với `ORDER BY created_at DESC` và không được N+1 như `GET /bookings`.
- Sau hủy mềm, `booking_seats` giữ cả bản ghi đã giải phóng. Kiểm tra truy vấn ghế đã đặt có dùng partial index hay không khi tỷ lệ bản ghi đã hủy cao.

**Dataset lớn (S7):** khoảng 30 ngày × 20 suất/ngày, phòng 300 ghế, khoảng 200.000 `booking_seats` (khoảng 30% đã hủy), một nhóm user có 200–500 booking. Con số chốt lại ở tuần 1 theo thời gian seed thực tế.

**Cách làm:**
1. Bật `pg_stat_statements` trên Kaggle; lấy các query tốn nhiều thời gian nhất trong lúc chạy S3 trên dataset lớn.
2. Chạy `EXPLAIN (ANALYZE, BUFFERS)` trước và sau khi sửa.
3. Thêm test đếm số query mỗi request bằng Hibernate statistics, để N+1 không quay lại.

Sửa bằng fetch theo lô (`findAllByBookingIdIn`) và index thiếu nếu `EXPLAIN` chỉ ra. Mỗi index mới là một migration Flyway mới.

**Giả thuyết:** p95 của `GET /bookings` hiện tăng tuyến tính theo số booking của user; sau khi sửa gần như không đổi.

**Đánh đổi:** batch fetch làm mapping phức tạp hơn; thêm migration và index làm ghi chậm hơn một chút.

## 9. E5: observability

**Cách làm:**
- Thêm `micrometer-registry-prometheus`; mở `/actuator/prometheus` ở profile `local`, `test` và profile đo. Không mở công khai ở `prod`.
- Metric cần có: `http.server.requests` theo route template và status, `hikaricp.connections.*`, JVM, hit ratio của cache (E2), counter `booking.created` và `booking.seat_conflict`, số request bị limiter từ chối (E3).
- Kaggle: script lấy mẫu `/actuator/prometheus` mỗi 5 giây ghi ra file, đi kèm evidence.
- Local: `compose.observability.yaml` gồm Prometheus và Grafana, kèm một dashboard để demo.
- Tracing nằm ngoài phạm vi.

**Đo overhead:** S3 ở 50 VU và S5 ở bậc gần bão hòa, bật so với tắt export metrics, cùng tag. Nếu overhead lớn hơn 5% về throughput hoặc p95 thì giảm số nhãn (cardinality) rồi đo lại.

**Giá trị chính:** dùng metrics để **giải thích** kết quả E1–E4 (pool cạn, request chờ khóa, hit ratio), không chỉ báo con số.

## 10. E6: nhiều replica

**Điều kiện:** API stateless (JWT, không session). Cần kiểm tra Flyway khi hai instance cùng khởi động (khởi động lần lượt) và seeder Admin chạy lặp có an toàn không.

**Topology trên Kaggle** (không có Docker):

| Core | Cấu hình 1 instance | Cấu hình 2 instance |
|---|---|---|
| 0 | API 1 | API 1 |
| 1 | — | API 2 |
| 2 | PostgreSQL (và Redis nếu dùng) | PostgreSQL (và Redis nếu dùng) |
| 3 | nginx + k6 | nginx + k6 |

Cả hai cấu hình đều đi qua nginx để overhead của nginx như nhau. Kết quả cho thấy khả năng mở rộng theo chiều ngang khi thêm core, không suy ra công suất production.

**Cache khi có nhiều instance:**
- Caffeine riêng mỗi instance: sau khi Admin sửa dữ liệu, instance còn lại trả dữ liệu cũ cho đến khi hết TTL.
- Redis dùng chung (`spring.cache.type=redis`): không có dữ liệu cũ, nhưng thêm một lượt đi qua mạng.
- Đo **cửa sổ dữ liệu cũ:** script sửa một phim rồi liên tục GET qua nginx, ghi thời gian đến khi mọi response đều mới.

**Đo:**
- S5 với 1 và 2 instance (dùng Caffeine).
- S5 với 2 instance, so Caffeine và Redis.
- S1 một lượt qua nginx để xác nhận tính đúng của khóa ở database khi có nhiều instance.

**Giả thuyết:** 2 instance trên 2 core đạt khoảng 1,6–1,9× throughput của 1 instance trên 1 core nếu DB chưa là nút thắt. Redis làm cửa sổ dữ liệu cũ gần 0 nhưng tăng p95 GET so với Caffeine.

**Demo local:** `compose.scale.yaml` gồm nginx, 2 API, PostgreSQL, Redis.

## 11. Cấu hình tổng hợp

Cuối tuần 4, ghép các phương án được giữ từ E1–E5 vào tag `phase-2-final`. Chạy S3 (10/25/50/100 VU), S5 và S6, so với `phase-1-baseline` trong cùng một phiên. Đây là bảng kết luận chính của báo cáo.

## 12. Tiêu chí kết luận chung

1. Phương án làm sai tính đúng (double-booking, đặt vào suất đã hủy, cache trả dữ liệu cũ khi chỉ có một instance) bị loại.
2. Chỉ tính là cải thiện khi chênh lệch median lớn hơn khoảng min–max của cả hai phương án.
3. Không cải thiện thì giữ baseline và ghi kết luận "không cải thiện" kèm số liệu. Đây vẫn là kết quả hợp lệ.
4. Mỗi thí nghiệm kết luận kèm trade-off: độ phức tạp, vận hành, rủi ro dữ liệu cũ, request bị từ chối.

## 13. Ngoài phạm vi Pha 2

Thanh toán thật, giữ ghế tạm và hết hạn, microservices, message broker, distributed tracing.

Hủy mềm, quy tắc 30 phút, mã booking và Admin xem mọi booking **thuộc phạm vi** và làm ở bước 0 (mục 3). Chúng được làm trước khi gắn `phase-1-baseline` để A, B, C cùng chạy trên một schema.

Idempotency cho `POST /api/v1/bookings` được ghi nhận tại [ADR-006](adr/006-idempotency-booking.md) như một đề xuất cải thiện reliability và consistency. Hạng mục này chưa nằm trong E1–E6 và chưa tự động trở thành phần bắt buộc của Pha 2; nhóm chỉ triển khai sau Bước 0 nếu còn nguồn lực, nếu không chuyển sang Pha 3.

## 14. Ước lượng thời gian chạy trên Kaggle

| Thí nghiệm | Ước lượng mỗi phương án | Số phương án | Tổng |
|---|---|---|---|
| E1 (S1–S4) | 80 phút | 3 × 2 phiên | khoảng 8 giờ |
| E2 (S5 + S3 50 VU) | 30 phút | 2 | 1 giờ |
| E3 (S6) | 20 phút | 2 | 40 phút |
| E4 (S7 + S3) | 40 phút, chưa tính seed | 2 | 1,5 giờ |
| E5 (overhead) | 20 phút | 2 | 40 phút |
| E6 | 30 phút | 3 cấu hình | 1,5 giờ |
| Cấu hình tổng hợp | 60 phút | 2 | 2 giờ |

Tổng khoảng 15 giờ, chia 3–4 phiên (mỗi phiên tối đa 12 giờ). Phân bổ phiên xem [work_plan.md](work_plan.md).
