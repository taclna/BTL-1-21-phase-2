# ADR-006: Idempotency cho thao tác tạo booking

- Trạng thái: Proposed
- Ngày: 2026-11-16
- Liên quan: [kế hoạch Pha 2](../ke-hoach.md), [ADR-002](002-kiem-soat-tranh-ghe.md), [E1](../e1-tranh-ghe.md)

## Bối cảnh

`POST /api/v1/bookings` tạo booking và giữ ghế trong một transaction. Có trường hợp server đã commit thành công nhưng response bị mất do timeout, mất kết nối hoặc client đóng kết nối sớm. Client không biết kết quả nên gửi lại cùng request.

Nếu không có idempotency, request gửi lại có thể tạo thêm booking hoặc nhận lỗi `SEAT_ALREADY_BOOKED` do chính request trước đã giữ ghế. Khi có nhiều API instance sau nginx, một lần retry cũng có thể được chuyển tới instance khác nên bộ nhớ cục bộ của một instance không giải quyết được vấn đề.

Idempotency không thay thế cơ chế chống bán trùng ghế của ADR-002:

- Partial unique index bảo vệ một ghế khỏi các yêu cầu đặt vé khác nhau.
- Idempotency bảo vệ một ý định đặt vé bị gửi lại do retry.

Thuộc tính chất lượng cần cải thiện là reliability, consistency và khả năng retry an toàn.

## Phạm vi

Áp dụng cho `POST /api/v1/bookings`. Chưa áp dụng cho các GET vì GET vốn không tạo side effect, và chưa áp dụng cho DELETE booking trong ADR này.

Thiết kế phải hoạt động khi chạy một hoặc nhiều API instance và không phụ thuộc sticky session ở nginx.

## Các phương án

| | Phương án | Ưu | Nhược |
|---|---|---|---|
| 1 | Không hỗ trợ idempotency | Không thêm code hay bảng | Retry có thể tạo kết quả trùng hoặc trả lỗi gây nhầm lẫn |
| 2 | Lưu key trong bộ nhớ từng API instance | Dễ làm, nhanh | Mất khi restart; không hoạt động đúng với nhiều replica; khó đồng bộ với transaction |
| 3 | Lưu key trong Redis | Dùng chung giữa các replica | Phải phối hợp Redis với transaction PostgreSQL; có nguy cơ DB commit nhưng Redis chưa lưu hoặc ngược lại |
| 4 | Lưu key trong PostgreSQL cùng transaction tạo booking | Atomic với booking; hoạt động với nhiều replica; không thêm hạ tầng | Thêm bảng, index, query và chính sách lưu giữ dữ liệu |
| 5 | Client tự cấp `bookingId` | Có thể tận dụng primary key để chống trùng | Làm lộ chi tiết định danh persistence trong API và vẫn cần kiểm tra cùng ID có cùng payload hay không |

## Quyết định đề xuất

Chọn phương án 4: lưu idempotency key trong PostgreSQL và xử lý nó trong cùng transaction với việc tạo booking.

Client gửi một UUID mới cho mỗi ý định tạo booking:

```http
POST /api/v1/bookings
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
Content-Type: application/json
```

Key được scope theo user đã xác thực. Hai user có thể dùng cùng chuỗi key mà không ảnh hưởng lẫn nhau.

### Contract

| Trường hợp | Kết quả |
|---|---|
| Thiếu key hoặc key không phải UUID hợp lệ | `400 INVALID_IDEMPOTENCY_KEY` |
| Key mới, booking được tạo | `201 Created`, response Booking bình thường |
| Key cũ, payload giống và booking đã được tạo | `200 OK`, trả cùng booking và header `Idempotency-Replayed: true` |
| Key cũ nhưng payload khác | `409 IDEMPOTENCY_KEY_REUSED` |
| Nghiệp vụ thất bại và transaction rollback | Không giữ key; client được phép retry cùng key |

Response lỗi dùng error envelope chung và có `requestId`. `Idempotency-Key` không thay thế JWT; user vẫn được xác định từ access token.

### Dữ liệu

Thêm bảng bằng Flyway, tên đề xuất `booking_idempotency`:

```text
user_id           uuid         not null
idempotency_key   uuid         not null
request_hash      char(64)     not null
booking_id        uuid         null
created_at        timestamptz  not null
expires_at        timestamptz  not null
primary key (user_id, idempotency_key)
foreign key (user_id) references users(id)
foreign key (booking_id) references bookings(id)
```

`request_hash` là SHA-256 của biểu diễn chuẩn hóa gồm `showtimeId` và danh sách `seatIds`. Vì thứ tự ghế không thay đổi ý nghĩa booking, `seatIds` được kiểm tra không trùng rồi sắp xếp trước khi tính hash.

`booking_id` được phép null chỉ trong transaction đang sở hữu key, trước khi booking được tạo. Cùng transaction phải cập nhật trường này trước khi commit; không được để tồn tại bản ghi đã commit có `booking_id` null. Transaction bị lỗi sẽ rollback cả bản ghi idempotency nên không để lại trạng thái dở dang.

Bản ghi được giữ ít nhất 24 giờ, dài hơn cửa sổ retry thông thường của client. Sau thời gian này có thể xóa theo lô; client không được kỳ vọng cùng key còn được replay sau khi hết hạn.

### Xử lý đồng thời

Luồng đề xuất trong một transaction:

1. Xác thực key và request, rồi tính `request_hash`.
2. Chèn quyền sở hữu key bằng `INSERT ... ON CONFLICT DO NOTHING`.
3. Nếu chèn thành công, thực hiện luồng tạo booking của ADR-002 và lưu `booking_id` trước khi commit.
4. Nếu key đã tồn tại, đọc bản ghi hiện có. PostgreSQL sẽ phối hợp hai transaction tranh cùng unique key.
5. Nếu hash khác, trả `409 IDEMPOTENCY_KEY_REUSED`; nếu hash giống, trả booking đã tạo trước đó.

Bản ghi idempotency và booking phải commit hoặc rollback cùng nhau. Không được lưu key thành công trước rồi tạo booking bằng một transaction khác.

Nếu hai request cùng user, key và payload đến đồng thời, chỉ một transaction tạo booking; request còn lại chờ kết quả unique-key conflict rồi replay cùng booking. Nếu transaction đầu rollback, transaction còn lại có thể sở hữu key và xử lý lại.

## Ràng buộc triển khai

- Không lưu idempotency state bằng static map hoặc cache Caffeine.
- Không dùng Redis làm nguồn sự thật cho idempotency trong quyết định này.
- Không cache method xử lý idempotency hoặc method khóa suất/ghế.
- Không ghi request body, JWT hoặc dữ liệu nhạy cảm vào log. Có thể log request ID và trạng thái `created`/`replayed`/`reused`.
- Repository idempotency thuộc module `booking`; module khác không truy cập trực tiếp bảng này.
- Logic claim key phải nằm trong transaction bao quanh toàn bộ thao tác tạo booking.
- Unique constraint giữ vai trò quyết định dưới concurrency; kiểm tra `exists` ở application không đủ.

## Quan sát và đo lường

Thêm counter:

- `booking.idempotency.created`
- `booking.idempotency.replayed`
- `booking.idempotency.reused`

Không dùng `userId`, `bookingId` hoặc idempotency key làm metric label vì gây cardinality cao.

Đo overhead bằng cách chạy cùng workload tạo booking có key mới với cơ chế bật và tắt. Báo cáo chênh lệch throughput, p95 POST và số query. Idempotency được giữ nếu bảo đảm tính đúng; overhead là trade-off cần ghi nhận, không phải lý do để bỏ tính đúng.

## Kiểm thử chấp nhận

1. Gửi cùng user, key và payload hai lần tuần tự: database có đúng một booking; lần hai trả cùng `bookingId`.
2. Gửi cùng user và key nhưng payload khác: trả `409 IDEMPOTENCY_KEY_REUSED` và không tạo booking mới.
3. Gửi đồng thời nhiều request cùng user, key và payload qua hai API instance: chỉ một booking được tạo, mọi response thành công tham chiếu cùng booking.
4. Giả lập transaction tạo booking thất bại: bản ghi idempotency không còn và cùng key có thể retry.
5. Giả lập DB commit thành công nhưng client không nhận response: retry trả booking đã commit.
6. Hai user dùng cùng key: mỗi user vẫn có namespace idempotency riêng.
7. Key thiếu hoặc sai định dạng: trả `400 INVALID_IDEMPOTENCY_KEY` theo error envelope.
8. Cùng request nhưng `seatIds` khác thứ tự: được xem là cùng payload sau chuẩn hóa.

## Hệ quả

### Tích cực

- Client có thể retry an toàn sau timeout hoặc mất response.
- Không tạo booking thứ hai do cùng một ý định bị gửi lại.
- Hoạt động nhất quán khi có nhiều API instance sau nginx.
- Trạng thái idempotency và booking được bảo đảm atomic bởi PostgreSQL.

### Đánh đổi

- Mỗi request tạo booking có thêm thao tác đọc/ghi và một unique index.
- Cần migration, cleanup bản ghi hết hạn và theo dõi kích thước bảng.
- API contract thêm header bắt buộc, nên script k6, OpenAPI và client phải cập nhật cùng lúc.
- Cần phân biệt rõ retry cùng ý định với một ý định đặt vé mới; client phải sinh key mới cho ý định mới.

## Trạng thái triển khai

ADR này mới là đề xuất, chưa được triển khai trong code hiện tại. Chỉ chuyển sang Accepted sau khi Bước 0 hoàn thành, contract được cập nhật, các test chấp nhận pass và nhóm xác nhận đưa idempotency vào phạm vi Pha 2 hoặc Pha 3.
