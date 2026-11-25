# Sơ đồ luồng nghiệp vụ hệ thống quản lý rạp phim

Tài liệu mô tả luồng nghiệp vụ theo mã nguồn hiện tại, đối chiếu ngày 29/10/2026. Luồng xem và hủy booking được cập nhật ngày 24/11/2026 theo Bước 0 của Pha 2 (hủy mềm, mốc 30 phút, quyền Admin). Sơ đồ dùng Mermaid; mở file bằng trình xem Markdown hỗ trợ Mermaid để hiển thị.

## 1. Phạm vi và tác nhân

| Tác nhân | Hoạt động |
|---|---|
| Khách vãng lai (Guest) | Xem phim, lịch chiếu, trạng thái ghế; đăng ký và đăng nhập |
| Khách hàng (Customer) | Đặt ghế, xem danh sách/chi tiết và hủy booking của mình sau khi đăng nhập |
| Quản trị viên (Admin) | Quản lý phim, phòng và sơ đồ ghế; tạo và hủy suất chiếu; xem và hủy mọi booking |
| Hệ thống | Xác thực, kiểm tra điều kiện nghiệp vụ, tính giá, lưu booking và bảo vệ ghế khỏi đặt trùng |

Hệ thống phục vụ một địa điểm rạp có nhiều phòng chiếu. Booking là một lần đặt từ 1 đến 8 ghế trong cùng một suất chiếu. Các hoạt động dưới đây được thực hiện qua API; dự án chưa có giao diện khách hàng riêng.

## 2. Sơ đồ nghiệp vụ tổng thể

```mermaid
flowchart TD
    START([Bắt đầu]) --> ROLE{Nhu cầu sử dụng}

    subgraph ADMIN[Quản trị và chuẩn bị lịch chiếu]
        A1[Admin đăng nhập] --> A2{Xác thực và quyền Admin hợp lệ?}
        A2 -->|Không| A3[Thông báo lỗi truy cập]
        A2 -->|Có| A4[Quản lý thông tin phim]
        A4 --> A5[Chuẩn bị phòng và sơ đồ ghế]
        A5 --> A6[Chọn phim, phòng, giờ bắt đầu và giá cơ bản]
        A6 --> A7{Dữ liệu hợp lệ và lịch không chồng lấn?}
        A7 -->|Không| A8[Thông báo lỗi và điều chỉnh lịch]
        A8 --> A6
        A7 -->|Có| A9[Tạo suất chiếu SCHEDULED]
    end

    subgraph CUSTOMER[Tra cứu và đặt vé]
        C1[Xem danh sách và chi tiết phim] --> C2[Xem lịch và chọn suất chiếu]
        C2 --> C3[Xem trạng thái ghế AVAILABLE hoặc BOOKED]
        C3 --> C4[Chọn từ 1 đến 8 ghế]
        C4 --> C5{Đã đăng nhập hợp lệ?}
        C5 -->|Chưa| C6[Đăng ký nếu cần, sau đó đăng nhập]
        C6 --> C5
        C5 -->|Rồi| C7[Gửi yêu cầu đặt ghế]
    end

    subgraph SYSTEM[Xử lý đặt vé]
        S1[Kiểm tra dữ liệu, suất chiếu và ghế] --> S2{Đủ điều kiện đặt?}
        S2 -->|Không| S3[Thông báo lỗi đặt vé]
        S2 -->|Có| S4[Tính giá ghế và tổng tiền]
        S4 --> S5[Lưu booking và các ghế trong một giao dịch]
        S5 --> S6{Lưu thành công?}
        S6 -->|Không| S7[Hoàn tác giao dịch và trả lỗi]
        S6 -->|Có| S8[Trả booking; ghế chuyển thành BOOKED]
    end

    ROLE -->|Quản trị| A1
    ROLE -->|Đặt vé| C1
    A9 -.->|Cung cấp lịch chiếu| C2
    C7 --> S1
    S3 --> C3
    S7 --> C3
    S8 --> C8[Xem booking của mình]
    C8 --> C9{Muốn hủy booking?}
    C9 -->|Có| C10{Booking CONFIRMED và còn ít nhất 30 phút trước giờ chiếu?}
    C10 -->|Có| C11[Chuyển booking sang CANCELLED và giải phóng ghế]
    C10 -->|Không| C12[Từ chối hủy; booking giữ nguyên]
    C9 -->|Không| FINISH([Kết thúc])
    C11 --> FINISH
    C12 --> FINISH
    ROLE -->|Admin quản lý booking| D1[Admin đăng nhập và xem mọi booking]
    D1 --> C9
    A3 --> FINISH
```

Các đường quay lại chọn ghế biểu diễn cách khách có thể thao tác tiếp sau lỗi; API không tự động chọn hoặc thử đặt ghế thay khách.

## 3. Luồng đăng ký và đăng nhập

```mermaid
flowchart TD
    I0([Cần sử dụng chức năng có xác thực]) --> I1{Đã có tài khoản?}
    I1 -->|Chưa| I2[Nhập tên đăng nhập và mật khẩu đăng ký]
    I2 --> I3{Dữ liệu hợp lệ và tên chưa được dùng?}
    I3 -->|Không| I4[Thông báo lỗi đăng ký]
    I4 --> I2
    I3 -->|Có| I5[Mã hóa mật khẩu và tạo tài khoản CUSTOMER]
    I5 --> I6[Nhập thông tin đăng nhập]
    I1 -->|Rồi| I6
    I6 --> I7{Thông tin đăng nhập đúng?}
    I7 -->|Không| I8[Thông báo lỗi đăng nhập]
    I8 --> I6
    I7 -->|Có| I9[Cấp JWT chứa danh tính và vai trò]
    I9 --> I10[Gửi JWT khi gọi chức năng cần xác thực]
    I10 --> I11{Token hợp lệ và đủ quyền?}
    I11 -->|Không| I12[Từ chối truy cập]
    I11 -->|Có| I13([Thực hiện nghiệp vụ])
```

Đăng ký công khai luôn tạo tài khoản `CUSTOMER`. Các API quản trị yêu cầu vai trò `ADMIN`. Khi xử lý booking, hệ thống lấy mã người dùng từ JWT đã xác thực.

## 4. Luồng đặt ghế chi tiết

```mermaid
flowchart TD
    B0([Nhận yêu cầu đặt ghế từ người dùng đã xác thực]) --> B1{Có mã người dùng, mã suất và 1 đến 8 mã ghế không rỗng, không trùng?}
    B1 -->|Không| E1[Từ chối dữ liệu đầu vào]
    B1 -->|Có| B2[Khóa suất chiếu đến hết giao dịch và lấy thông tin ghế]
    B2 --> B3{Suất tồn tại, SCHEDULED, chưa bắt đầu và có giá cơ bản dương?}
    B3 -->|Không| E2[Từ chối suất chiếu không hợp lệ]
    B3 -->|Có| B4{Đủ ghế yêu cầu, đúng phòng và có loại ghế hợp lệ?}
    B4 -->|Không| E3[Từ chối danh sách ghế]
    B4 -->|Có| B5{Tất cả ghế còn trống trong suất này?}
    B5 -->|Không| E4[Thông báo ghế đã được đặt]
    B5 -->|Có| B6[Tính giá từng ghế theo loại STANDARD hoặc VIP]
    B6 --> B7[Cộng giá các ghế thành tổng tiền]
    B7 --> B8[Tạo booking và lưu toàn bộ ghế]
    B8 --> B9{Giao dịch lưu thành công?}
    B9 -->|Không| E5[Hoàn tác toàn bộ booking và ghế; trả lỗi]
    B9 -->|Có| B10[Trả chi tiết booking; các ghế được ghi nhận BOOKED]
    B10 --> B11([Đặt vé thành công])
    E1 --> FAIL([Đặt vé thất bại])
    E2 --> FAIL
    E3 --> FAIL
    E4 --> FAIL
    E5 --> FAIL
```

| Quy tắc | Nội dung |
|---|---|
| Số lượng | Mỗi booking có 1–8 ghế; không chấp nhận mã ghế trùng hoặc rỗng |
| Suất chiếu | Phải tồn tại, có trạng thái `SCHEDULED` và thời điểm bắt đầu nằm trong tương lai |
| Ghế | Phải thuộc phòng của suất chiếu và chưa được đặt trong chính suất đó |
| Giá STANDARD | Bằng giá cơ bản của suất chiếu |
| Giá VIP | Bằng giá cơ bản nhân `1.20` |
| Tổng tiền | Tổng giá đã chốt của từng ghế; giá ghế và tổng tiền làm tròn 2 chữ số thập phân, `HALF_UP` |
| Nguồn giá | Hệ thống tự tính từ dữ liệu suất chiếu và loại ghế, không nhận giá do khách tự khai |
| Chống đặt trùng | Khóa suất chiếu trong giao dịch; partial unique index cho cặp suất chiếu và ghế, chỉ tính ghế chưa giải phóng (`released_at IS NULL`), nên ghế của booking đã hủy được đặt lại |
| Tính toàn vẹn | Booking và các ghế được lưu cùng giao dịch; lưu thất bại thì hoàn tác toàn bộ |

**Lưu ý về thanh toán:** booking hiện được ghi nhận ngay khi lưu thành công và giữ ghế. Trường `paymentStatus` khởi tạo là `PENDING`, nhưng chưa có luồng thanh toán thật hay tự động hết hạn. Đây là trạng thái thanh toán, không phải trạng thái giữ ghế tạm.

## 5. Luồng xem và hủy booking

### 5.1. Xem booking

```mermaid
flowchart TD
    V0([Người dùng đã đăng nhập]) --> V1{Vai trò trong JWT}
    V1 -->|CUSTOMER| V2[Xem danh sách booking của mình, có phân trang]
    V1 -->|ADMIN| V3[Xem danh sách mọi booking, có phân trang]
    V2 --> V4[Chọn một booking]
    V3 --> V4
    V4 --> V5{Là ADMIN?}
    V5 -->|Có| V6[Tìm theo bookingId]
    V5 -->|Không| V7[Tìm theo bookingId và mã người dùng từ JWT]
    V6 --> V8{Tìm thấy?}
    V7 --> V8
    V8 -->|Không| V9[Trả 404 BOOKING_NOT_FOUND]
    V8 -->|Có| V10[Trả chi tiết booking, kể cả booking CANCELLED]
    V9 --> V11([Kết thúc])
    V10 --> V11
```

### 5.2. Hủy booking (hủy mềm)

```mermaid
flowchart TD
    C0([Yêu cầu hủy booking từ người dùng đã đăng nhập]) --> C1{Là ADMIN?}
    C1 -->|Có| C2[Tìm và khóa booking theo bookingId]
    C1 -->|Không| C3[Tìm và khóa booking theo bookingId và mã người dùng từ JWT]
    C2 --> C4{Tìm thấy?}
    C3 --> C4
    C4 -->|Không| C5[Trả 404 BOOKING_NOT_FOUND]
    C4 -->|Có| C6{Trạng thái là CONFIRMED?}
    C6 -->|Không| C7[Trả 409 BOOKING_CANNOT_BE_CANCELLED]
    C6 -->|Có| C8{Tìm thấy suất và còn ít nhất 30 phút trước giờ chiếu?}
    C8 -->|Không| C7
    C8 -->|Có| C9[Chuyển booking sang CANCELLED, ghi cancelled_at]
    C9 --> C10[Ghi released_at cho mọi ghế của booking]
    C10 --> C11{Giao dịch thành công?}
    C11 -->|Không| C12[Hoàn tác và trả lỗi; booking và ghế giữ nguyên]
    C11 -->|Có| C13[Trả 204; ghế có thể được đặt lại]
    C5 --> C14([Kết thúc])
    C7 --> C14
    C12 --> C14
    C13 --> C14
```

### 5.3. Vòng đời booking

```mermaid
stateDiagram-v2
    [*] --> CONFIRMED: Đặt ghế thành công
    CONFIRMED --> CANCELLED: Hủy khi còn ít nhất 30 phút trước giờ chiếu
    CANCELLED --> [*]
```

| Quy tắc | Nội dung |
|---|---|
| Hủy mềm | Không xóa bản ghi. Booking chuyển sang `CANCELLED` kèm `cancelled_at`; ghế được giải phóng bằng `released_at`, trong cùng giao dịch |
| Mốc 30 phút | So với giờ bắt đầu suất chiếu. Còn đúng 30 phút vẫn hủy được |
| Hủy lại | Booking đã `CANCELLED` trả `409 BOOKING_CANNOT_BE_CANCELLED` |
| Quyền hủy | Customer chỉ hủy booking của mình; booking của người khác trả `404`. Admin hủy được mọi booking nhưng vẫn theo trạng thái và mốc 30 phút |
| Hủy đồng thời | Hàng booking bị khóa trong giao dịch, nên chỉ một yêu cầu hủy thành công; yêu cầu sau nhận `409` |
| Đặt lại ghế | Ghế có `released_at` được coi là trống; lịch sử booking cũ vẫn còn |
| Thanh toán | Hủy booking không đổi `paymentStatus`; chưa có hoàn tiền |

## 6. Luồng tạo và hủy suất chiếu của Admin

```mermaid
flowchart TD
    A0([Admin đã đăng nhập và có quyền]) --> A1{Thao tác}
    A1 -->|Tạo suất| A2[Chọn phim, phòng, giờ bắt đầu và giá cơ bản dương]
    A2 --> A3{Phim tồn tại, chưa xóa và phòng đang hoạt động?}
    A3 -->|Không| ERR[Thông báo lỗi nghiệp vụ]
    A3 -->|Có| A4{Giờ bắt đầu nằm trong tương lai?}
    A4 -->|Không| ERR
    A4 -->|Có| A5[Tính giờ kết thúc bằng giờ bắt đầu cộng thời lượng phim cộng 15 phút dọn phòng]
    A5 --> A6{Trùng khoảng lịch chiếu trong cùng phòng?}
    A6 -->|Có| ERR
    A6 -->|Không| A7[Lưu suất chiếu SCHEDULED]
    A7 --> DONE([Thành công])

    A1 -->|Hủy suất| A8[Chọn và khóa suất chiếu]
    A8 --> A9{Suất tồn tại, SCHEDULED và chưa bắt đầu?}
    A9 -->|Không| ERR
    A9 -->|Có| A10{Còn booking CONFIRMED của suất chiếu?}
    A10 -->|Có| A11[Từ chối hủy suất có booking CONFIRMED]
    A10 -->|Không| A12[Chuyển suất sang CANCELLED]
    A12 --> DONE
    A11 --> STOP([Không thay đổi suất chiếu])
    ERR --> STOP
```

- Admin cần chuẩn bị phim, phòng và sơ đồ ghế trước khi tạo suất; phim và phòng có thể được tạo theo thứ tự bất kỳ.
- Phim được xóa mềm. Phòng cũng được xóa mềm và không được xóa khi còn suất `SCHEDULED` trong tương lai.
- Tạo booking và hủy suất dùng chung cơ chế khóa suất chiếu để tránh đồng thời đặt thành công vào một suất vừa bị hủy.
- Guard hủy suất chỉ xét booking `CONFIRMED`, kể cả khi `paymentStatus = PENDING`. Booking đã `CANCELLED` không chặn việc hủy suất.

## 7. Phân biệt hiện trạng và yêu cầu dự kiến

Bảng dưới ghi hiện trạng trong mã nguồn của các nội dung nêu ở tài liệu đặc tả/yêu cầu; một số nội dung vẫn là định hướng, chưa triển khai:

| Nội dung | Hiện trạng dùng trong sơ đồ |
|---|---|
| Danh tính và quyền sở hữu booking | Đã lấy người dùng từ JWT; Customer xem/hủy theo cả `bookingId` và mã người dùng; Admin theo `bookingId` |
| Hủy booking | Đã hủy mềm, giải phóng ghế bằng `released_at`; chỉ hủy khi còn ít nhất 30 phút trước giờ chiếu |
| Trạng thái booking | Đã có `CONFIRMED`/`CANCELLED` và `cancelled_at` |
| Thanh toán | Có trường trạng thái thanh toán; chưa có quy trình thanh toán/hoàn tiền thật |
| Giữ ghế tạm và hết hạn | Chưa triển khai |
| Admin xem mọi booking | Đã có `GET /api/v1/admin/bookings` (phân trang); Admin xem chi tiết và hủy được mọi booking |

## 8. Nguồn đối chiếu

- [README dự án](../../README.md), [đặc tả hệ thống](SPEC.md) và [yêu cầu Booking](booking_requirements.md).
- [Xác thực và phân quyền](../../src/main/java/com/example/cinema/config/SecurityConfig.java).
- [Nghiệp vụ tài khoản](../../src/main/java/com/example/cinema/modules/identity/business/IdentityService.java).
- [API booking](../../src/main/java/com/example/cinema/modules/booking/api/BookingController.java), [API booking của Admin](../../src/main/java/com/example/cinema/modules/booking/api/AdminBookingController.java) và [nghiệp vụ booking](../../src/main/java/com/example/cinema/modules/booking/business/BookingService.java).
- [Lưu và hủy mềm booking, kiểm tra booking khi hủy suất](../../src/main/java/com/example/cinema/modules/booking/data/BookingRepositoryAdapter.java).
- [Migration hủy mềm booking](../../src/main/resources/db/migration/V9__soft_cancel_bookings.sql).
- [Nghiệp vụ suất chiếu](../../src/main/java/com/example/cinema/modules/theater/business/ShowtimeService.java) và [nghiệp vụ phòng chiếu](../../src/main/java/com/example/cinema/modules/theater/business/AuditoriumService.java).

Khi tài liệu cũ và mã nguồn khác nhau, tài liệu này ưu tiên hành vi trong mã nguồn để mô tả luồng hiện tại.
