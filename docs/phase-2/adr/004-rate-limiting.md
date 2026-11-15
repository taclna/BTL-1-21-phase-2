# ADR-004: Giới hạn tải (rate limiting / backpressure)

- Trạng thái: Proposed (chốt sau khi có số liệu E3)
- Ngày: 2026-11-15
- Liên quan: [kế hoạch Pha 2 mục 7](../ke-hoach.md#7-e3-rate-limiting--backpressure)

## Bối cảnh

Hiện API nhận mọi request. Khi tải vượt khả năng xử lý, request xếp hàng ở thread pool của Tomcat và ở Hikari (10 connection). Khi đó latency của mọi request cùng tăng, kể cả health check. Hành vi này chưa được đo ở Pha 1; S6 ở tuần 2 sẽ đo.

## Phương án

| | Phương án | Bảo vệ khỏi | Nhược |
|---|---|---|---|
| 1 | Không giới hạn (hiện tại) | — | Quá tải làm chậm tất cả |
| 2 | Giới hạn số request đang xử lý (semaphore trong filter), trả 429 khi đầy | Quá tải chung (availability) | Từ chối cả request hợp lệ; giới hạn tính theo từng instance |
| 3 | Token bucket theo user hoặc IP (ví dụ Bucket4j) | Một client gửi quá nhiều (lạm dụng, dò mật khẩu) | Không chống được quá tải do nhiều client; thêm thư viện |

## Quyết định (tạm thời)

Đo phương án 2 so với 1 trong E3. Phương án 3 cho `POST /auth/login` và `POST /bookings` là phần mở rộng, chỉ làm nếu còn thời gian.

Ràng buộc:
- Filter nằm trong `config`, không chứa logic nghiệp vụ.
- Response 429 dùng error envelope chung, code `TOO_MANY_REQUESTS`, có header `Retry-After`.
- Miễn giới hạn cho `/actuator/health/**` để orchestrator vẫn kiểm tra được.
- Giới hạn chọn từ điểm bão hòa đo được, ghi rõ con số và lý do trong ADR khi chốt.

## Hệ quả

Viết sau khi có số liệu: goodput, p95 của request được nhận, tỷ lệ 429, thời gian hồi phục sau spike.
