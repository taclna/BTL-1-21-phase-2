# Phân công Pha 2

> Thời gian: 16/11 – 20/12/2026 (5 tuần) · Nội dung kỹ thuật: [ke-hoach.md](ke-hoach.md)\
> Giữ cách chia module của [Pha 1](../phase-1/work_plan.md): mỗi người chịu trách nhiệm module của mình xuyên ba tầng.

## 1. Quyết định đã chốt (15/11/2026)

| # | Quyết định |
|---|---|
| 1 | Làm đủ 6 thí nghiệm E1–E6, cắt theo thứ tự ưu tiên ở mục 5 nếu bị trễ |
| 2 | Đưa hủy mềm, quy tắc hủy trước 30 phút, mã booking và Admin xem mọi booking vào Pha 2. Làm ở bước 0, trước khi gắn `phase-1-baseline`, để mọi phương án đo trên cùng một schema |
| 3 | Response thành công của Booking được bọc `{data: ...}` theo SPEC 7.1 |
| 4 | Phương án D của E1 (`SERIALIZABLE` + retry) chỉ làm nếu tuần 3 còn thời gian |
| 5 | Mỗi phương án là một tag; các phương án của một thí nghiệm đo trong cùng một phiên Kaggle |

## 2. Vai trò

| Người | Module | Phụ trách ở Pha 2 | Review chéo |
|---|---|---|---|
| Người 1 | identity, hạ tầng | Bước 0: mã booking, Admin xem mọi booking (*); E5 observability; hạ tầng E6 (nginx, Redis, ghim CPU, compose demo); notebook chạy nhiều cấu hình; quản lý tag; duyệt mọi thay đổi `pom.xml`, `config`, `shared` | Người 4 |
| Người 2 | movie, k6 | Script S2, S5, S6, seed `hot` và `large`; E2 cache Movie và profile Redis; chạy đo Kaggle; viết báo cáo chính | Người 3 |
| Người 3 | theater | Method khóa chia sẻ (E1); E2 cache lịch chiếu và snapshot suất; E3 filter rate limiting; S4 `overlap.js`; script tổng hợp và biểu đồ | Người 2 |
| Người 4 | booking | Bước 0: sửa lỗi contract Booking, hủy mềm, quy tắc 30 phút; E1 phương án B, C; E4 sửa N+1, phân trang, index; chốt ADR-002 | Người 1 |

(*) Ngoại lệ có chủ đích với quy tắc "mỗi người sửa module của mình". Người 1 viết hai phần này trong module `booking` để giảm tải cho Người 4, vì Người 1 đã quản lý phân quyền Admin. Người 4 review và là chủ sở hữu code sau khi merge.

## 3. Lịch theo tuần

### Tuần 1 (16–22/11): hoàn thiện Pha 1 và nền tảng đo

| Người | Việc | Sản phẩm |
|---|---|---|
| 1 | Mã booking (cột, migration sinh mã cho booking cũ, trả trong response); `GET /api/v1/admin/bookings` có phân trang, Admin xem chi tiết và hủy mọi booking, test phân quyền 401/403; thêm `micrometer-registry-prometheus` và mở `/actuator/prometheus` ở profile đo | PR mã booking + Admin; PR metrics |
| 2 | Sửa `contention.js`, `verify-contention.sql`, `client-check.mjs`, `booking.js` theo shape `{data}` và schema hủy mềm; viết S5 `stress-read.js`; bắt đầu S2 `hot-showtime.js`; `SEED_SCOPE=hot` | Script chạy được ở local |
| 3 | Thêm method khóa chia sẻ vào `ShowtimeModuleApi` kèm unit test, **merge trước thứ Tư** để Người 4 dùng; bắt đầu S4 `overlap.js` | PR interface Theater |
| 4 | **Merge migration hủy mềm trước thứ Tư** để Người 1 và Người 2 làm trên schema mới. Sau đó: `SeatAlreadyBookedException` → 409; chuyển lỗi vi phạm partial index thành 409; error envelope; bọc `{data}`; quy tắc hủy trước 30 phút → `409 BOOKING_CANNOT_BE_CANCELLED`; integration test hai khách cùng ghế, hủy rồi đặt lại được ghế, hủy sát giờ bị từ chối | PR migration; PR Booking |

**Mốc:** `mvn verify` pass; S1 chạy ở local ra đúng 1 thắng và N−1 lỗi 409; Người 1 gắn tag **`phase-1-baseline`** chậm nhất **thứ Ba 24/11**.

### Tuần 2 (23–29/11): tìm nút thắt của A, bắt đầu cải tiến

| Người | Việc | Sản phẩm |
|---|---|---|
| 1 | Thêm vào `run-baseline.py` lấy mẫu `pg_stat_activity` (chờ khóa), đếm deadlock, lấy mẫu `/actuator/prometheus`; cài nginx, Redis và script ghim CPU trên Kaggle; cập nhật notebook để chạy lần lượt nhiều tag trong một phiên | Notebook chạy được A |
| 2 | Cập nhật `docs/phase-1/openapi.json` và README theo API mới; viết S6 `overload.js` và seed `SEED_SCOPE=large`; **từ thứ Tư 25/11, phiên Kaggle thử nghiệm với A:** S5 (điểm bão hòa đọc), S6 (quá tải), S1–S4 mỗi mức 1 lượt; bắt đầu cache Movie | "Bản đồ nút thắt của A" (1 trang, gửi cả nhóm) |
| 3 | Hoàn thiện S4; bắt đầu cache lịch chiếu và snapshot suất; integration test B với hủy suất đồng thời | PR cache Theater (nháp) |
| 4 | Cập nhật [so-do-luong-nghiep-vu.md](../phase-1/so-do-luong-nghiep-vu.md) theo luồng hủy mới; triển khai phương án B từ tag `phase-1-baseline`; chạy `EXPLAIN` ở local cho `GET /bookings` | B pass `mvn verify` |

**Mốc cuối tuần:** có bản đồ nút thắt của A; chốt mức tải cho S2, S5, S6; B pass `mvn verify`.

### Tuần 3 (30/11–6/12): hoàn thiện cải tiến, đóng băng code

| Người | Việc | Sản phẩm |
|---|---|---|
| 1 | Cấu hình 2 instance + nginx; `compose.scale.yaml`, `compose.observability.yaml` + dashboard Grafana; review và gắn các tag | Tag `phase-2-*` |
| 2 | Hoàn thiện cache Movie, profile Redis; script đo cửa sổ dữ liệu cũ; chạy thử cả ba tag E1 trên Kaggle | Tag `phase-2-cache` |
| 3 | Filter rate limiting, chọn giới hạn từ số liệu tuần 2; script tổng hợp so sánh nhiều phương án (CSV + biểu đồ) | Tag `phase-2-ratelimit` |
| 4 | Phương án C; test deadlock với booking nhiều ghế; sửa N+1, phân trang `GET /bookings`, index nếu cần; test đếm số query | Tag `phase-2-seat-lock`, `phase-2-query` |

**Mốc cuối tuần (điểm cắt):** mọi tag đã gắn và pass `mvn verify`. Thí nghiệm nào chưa đạt mốc này thì chuyển vào mục "hướng phát triển" của báo cáo, không kéo sang tuần 4. **Từ đây không sửa code đo.**

### Tuần 4 (7–13/12): đo chính thức

| Phiên Kaggle | Nội dung | Thời gian ước lượng |
|---|---|---|
| Phiên 1 (đầu tuần) | E1 thứ tự A→B→C; E2; E3; E5 | khoảng 6,5 giờ |
| Phiên 2 | E1 thứ tự C→B→A; E4 | khoảng 5,5 giờ |
| Phiên 3 (cuối tuần) | E6; cấu hình tổng hợp `phase-2-final` so với A | khoảng 3,5 giờ |

| Người | Việc |
|---|---|
| 1 | Hỗ trợ môi trường trong lúc chạy; ghép các phương án được giữ thành `phase-2-final` (sau phiên 2) |
| 2 | Chạy cả ba phiên; lưu evidence vào `docs/phase-2/performance/` |
| 3 | Tổng hợp số liệu ngay sau mỗi phiên, gửi bảng sơ bộ cho cả nhóm |
| 4 | Kiểm tra tính đúng trong DB sau mỗi lượt contention; điều tra bất thường |

Nếu phát hiện bug: sửa, gắn tag lại, rồi đo lại **toàn bộ thí nghiệm đó** trong cùng một phiên.

**Mốc cuối tuần:** đủ số liệu 3 lượt cho mọi phương án và cho cấu hình tổng hợp.

### Tuần 5 (14–20/12): phân tích và đóng gói, không thêm tính năng

| Người | Việc |
|---|---|
| 1 | Cập nhật README (kiến trúc Pha 2, cách chạy compose demo); chạy Docker trên máy sạch; gắn tag `phase-2-final`; mục observability của báo cáo |
| 2 | Viết báo cáo Pha 2: giả thuyết, số liệu, kết luận, trade-off; chốt ADR-003 |
| 3 | Biểu đồ; chốt ADR-004; review báo cáo |
| 4 | Chốt ADR-002 và ADR-005 kèm số liệu; merge các phương án được giữ vào `main` |

**Mốc cuối tuần:** báo cáo hoàn chỉnh; ADR-002 đến ADR-005 ở trạng thái Accepted hoặc Rejected; tag `phase-2-final`.

## 4. Sản phẩm cuối Pha 2

- Booking hoàn chỉnh theo SPEC: hủy mềm, quy tắc 30 phút, mã booking, Admin xem mọi booking; OpenAPI và sơ đồ luồng nghiệp vụ cập nhật theo.
- Báo cáo Pha 2 trong `docs/phase-2/` kèm bảng so sánh và biểu đồ của từng thí nghiệm.
- ADR-002 đến ADR-005 đã chốt.
- Evidence: CSV tổng hợp, `environment.json`, ZIP raw k6 của mỗi phiên.
- Script k6 S2, S4, S5, S6, seed `hot` và `large`, notebook Kaggle Pha 2.
- `compose.scale.yaml`, `compose.observability.yaml`, dashboard Grafana.
- Code các phương án được giữ đã merge vào `main`, `mvn verify` pass.

## 5. Ưu tiên và điểm cắt

| Mức | Thí nghiệm | Khi bị trễ |
|---|---|---|
| Bắt buộc | Bước 0 (sửa lỗi contract và 4 nghiệp vụ bổ sung), E1, E2, E5 | Không cắt; dồn người từ mức thấp hơn sang |
| Nên có | E3, E4 | Cắt nếu chưa gắn tag ở mốc tuần 3 |
| Có thể bỏ | E6 | Cắt đầu tiên; vẫn giữ `compose.scale.yaml` làm demo nếu đã có |

Điểm kiểm tra: buổi họp cuối tuần 2 (xem có kịp không) và mốc cuối tuần 3 (quyết định cắt).

## 6. Quy tắc làm việc

- Mỗi người chỉ sửa package, migration và cache của module mình (ngoại lệ duy nhất ghi ở mục 2); thay đổi `pom.xml`, `config`, `shared` phải được Người 1 duyệt.
- Interface giữa các module merge sớm; module phụ thuộc dùng fake hoặc mock, không chờ.
- Branch ngắn, merge tối đa 2 ngày một lần; mỗi phương án đo là một tag, không đo từ code chưa commit.
- Mỗi người tự viết test cho phần mình; mỗi cải tiến phải có test tính đúng đi kèm (xóa cache, 409, 429, số query).
- Không nới ngưỡng hay giảm tải để làm đẹp kết quả; chưa đo thì ghi "chưa đo".
- Họp ngắn cuối mỗi tuần để kiểm tra mốc.
