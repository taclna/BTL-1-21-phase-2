# Phân công Pha 2

> Thời gian: 16/11 – 20/12/2026 (5 giai đoạn, 5 checkpoint) · Nội dung kỹ thuật: [ke-hoach.md](ke-hoach.md)\
> Giữ cách chia module của [Pha 1](../phase-1/work_plan.md): mỗi người chịu trách nhiệm module của mình xuyên ba tầng.\
> Cập nhật 21/11/2026: chia lại Bước 0 — Người 1 làm 0.1–0.8, Người 4 làm 0.9–0.11; ADR-006 vẫn thuộc Pha 2 dưới dạng thí nghiệm E7.

## 1. Quyết định đã chốt

| # | Ngày | Quyết định |
|---|---|---|
| 1 | 15/11 | Làm đủ các thí nghiệm E1–E7, cắt theo thứ tự ưu tiên ở mục 6 nếu bị trễ |
| 2 | 15/11 | Đưa hủy mềm, quy tắc hủy trước 30 phút, mã booking và Admin xem mọi booking vào Pha 2. Làm ở Bước 0, trước khi gắn `phase-1-baseline`, để mọi phương án đo trên cùng một schema |
| 3 | 15/11 | Response thành công của Booking được bọc `{data: ...}` theo SPEC 7.1 |
| 4 | 15/11 | Phương án D của E1 (`SERIALIZABLE` + retry) chỉ làm nếu GĐ3 còn thời gian |
| 5 | 15/11 | Mỗi phương án là một tag; các phương án của một thí nghiệm đo trong cùng một phiên Kaggle |
| 6 | 16/11 | Ưu tiên E4 trước E2: phân trang Booking làm ở Bước 0; đo và sửa N+1/query/index trước khi đo cache |
| 7 | 20/11 | Idempotency ([ADR-006](adr/006-idempotency-booking.md)) làm **trong Pha 2** thành thí nghiệm E7, trên tag riêng `phase-2-idempotency`, không nằm trong Bước 0. Script k6 gửi `Idempotency-Key` từ GĐ1 để A, B, C của E1 vẫn chạy cùng contract. Người 1 làm, Người 4 review |
| 8 | 21/11 | Chia lại Bước 0: Người 1 chịu trách nhiệm toàn bộ 0.1–0.8 (sửa contract Booking, hủy mềm và quy tắc 30 phút); Người 4 chịu trách nhiệm 0.9–0.11 (mã booking, API Admin và phân trang). Người 1 ưu tiên merge 0.7 thành CP1a để mở khóa phần việc phụ thuộc schema của Người 4. Cập nhật sơ đồ luồng nghiệp vụ vẫn do Người 3 làm |

## 2. Vai trò

| Người | Module | Phụ trách ở Pha 2 | Review chéo |
|---|---|---|---|
| Người 1 | identity, hạ tầng | Bước 0: 0.1–0.8 — sửa contract Booking, hủy mềm, quy tắc 30 phút (*); E7 idempotency (*); E5 observability; hạ tầng E6 (nginx, Redis, ghim CPU, compose demo); notebook chạy nhiều cấu hình; quản lý tag; duyệt mọi thay đổi `pom.xml`, `config`, `shared` | Người 4 |
| Người 2 | movie, k6 | Script k6 (sửa S1/S3, viết S2, S5, S6), seed `large` và `hot`; E2 cache Movie và profile Redis; chạy đo Kaggle; OpenAPI và README; viết báo cáo chính | Người 3 |
| Người 3 | theater | Method khóa chia sẻ (E1); S4 `overlap.js`; E2 cache lịch chiếu và snapshot suất; E3 rate limiting; cập nhật sơ đồ luồng nghiệp vụ; script tổng hợp và biểu đồ | Người 2 |
| Người 4 | booking | Bước 0: 0.9–0.11 — mã booking, Admin xem mọi booking, phân trang `GET /bookings`; E4 sửa N+1 và index; E1 phương án B, C; review E7; chốt ADR-002 | Người 1 |

(*) Ngoại lệ có chủ đích với quy tắc "mỗi người sửa module của mình". Người 1 viết 0.1–0.8 và E7 trong module `booking`; Người 4 review và là chủ sở hữu code sau khi merge. Người 1 review chéo phần 0.9–0.11 của Người 4.

**Đường găng:** migration hủy mềm (Người 1) → 0.9–0.11 của Người 4 và các việc còn lại của Bước 0 → tag `phase-1-baseline` → mọi phép đo. Trễ ở đây kéo trễ cả nhóm, nên Người 1 phải ưu tiên CP1a trước các việc khác; khi đường găng bị chặn, cả nhóm ưu tiên gỡ trước việc của mình.

## 3. Tổng quan giai đoạn

| Giai đoạn | Thời gian | Mục tiêu | Checkpoint | Hạn |
|---|---|---|---|---|
| GĐ1 | 16/11 – 24/11 | Hoàn thiện Pha 1 theo SPEC (Bước 0), dựng công cụ đo | **CP1: Baseline** | Thứ Ba 24/11 |
| GĐ2 | 25/11 – 29/11 | Tìm nút thắt của A; xong E4; bắt đầu B | **CP2: Bản đồ nút thắt** | Chủ nhật 29/11 |
| GĐ3 | 30/11 – 6/12 | Hoàn thiện mọi cải tiến; đóng băng code | **CP3: Code freeze** (điểm cắt) | Chủ nhật 6/12 |
| GĐ4 | 7/12 – 13/12 | Đo chính thức trên Kaggle | **CP4: Đủ số liệu** | Chủ nhật 13/12 |
| GĐ5 | 14/12 – 20/12 | Phân tích, chốt ADR, đóng gói; không thêm tính năng | **CP5: Nộp** | Chủ nhật 20/12 |

Mỗi checkpoint là một buổi họp ngắn. Người 1 điều phối và ghi kết quả "đạt / đạt có điều kiện / không đạt" vào mục 8 của file này.

## 4. Chi tiết từng giai đoạn

### GĐ1 (16/11 – 24/11): Bước 0 và công cụ đo

Chi tiết kỹ thuật của từng dòng 0.x ở [E1 mục 3](e1-tranh-ghe.md#3-bước-0-hoàn-thiện-pha-1-theo-spec).

**Mốc phụ CP1a: schema hủy mềm đã merge.** Người 1 chịu trách nhiệm. Hạn gốc thứ Tư 18/11. Nếu chưa merge, hạn cuối là **thứ Bảy 21/11**, vì Người 4 cần schema mới cho 0.9–0.11 và Người 2 cần contract/schema ổn định để cập nhật script.

| Người | Việc | Phụ thuộc | Sản phẩm |
|---|---|---|---|
| 1 | **Ưu tiên 1:** 0.7 migration hủy mềm + partial index (CP1a). Hoàn thiện 0.1–0.8: `SeatAlreadyBookedException` → 409; ánh xạ lỗi partial index → 409; error envelope; bọc `{data}`; cập nhật và chạy `contention.js`, `verify-contention.sql`, `client-check.mjs`, `booking.js` cho 0.4–0.5; integration test hai khách cùng ghế; quy tắc 30 phút → `409 BOOKING_CANNOT_BE_CANCELLED`. Đồng thời thêm `micrometer-registry-prometheus`, mở `/actuator/prometheus` ở profile đo | — | PR migration CP1a; PR contract + hủy Booking; PR script 0.4–0.5; PR metrics |
| 2 | Mọi `POST /bookings` trong script gửi header `Idempotency-Key` (UUID mới mỗi lần đặt); viết S5 `stress-read.js`; bắt đầu seed `SEED_SCOPE=large`; hỗ trợ rà lại script sau khi contract 0.4–0.5 được merge | Shape response chốt trong PR của Người 1 | Script Phase 2 chạy được ở local |
| 3 | Method khóa chia sẻ trong `ShowtimeModuleApi` kèm unit test; bắt đầu S4 `overlap.js` | — | PR interface Theater (merge sớm nhất có thể) |
| 4 | Sau CP1a, hoàn thiện 0.9 mã booking (cột, migration sinh mã cho booking cũ, trả trong response); 0.10 `GET /api/v1/admin/bookings` có phân trang, Admin xem chi tiết và hủy mọi booking, test 401/403; 0.11 phân trang `GET /bookings` và cập nhật `booking.js` theo response phân trang | CP1a; contract 0.3–0.4 của Người 1 đã chốt | PR mã booking; PR Admin + phân trang |

**CP1 (thứ Ba 24/11): Baseline**

- [ ] Toàn bộ 0.1–0.11 đã merge vào `main`
- [ ] `mvn verify` pass, gồm test cùng ghế, hủy rồi đặt lại, hủy sát giờ, phân quyền Admin
- [ ] S1 `contention.js` chạy ở local: đúng 1 thắng, N−1 lỗi 409 `SEAT_ALREADY_BOOKED`; `verify-contention.sql` trả `active_claims = 1`, `duplicate_groups = 0`
- [ ] S3 `booking.js` chạy hết một lượt ngắn ở local, không có lỗi ngoài dự kiến
- [ ] Người 1 gắn tag **`phase-1-baseline`**

Nếu không đạt: phần còn thiếu nhỏ (ví dụ test) cho thêm tối đa đến thứ Tư 25/11. Nếu thiếu phần làm đổi schema hoặc contract thì **không gắn tag**, mọi người dồn sang gỡ; GĐ2 lùi tương ứng và họp lại để quyết định cắt E6 sớm.

### GĐ2 (25/11 – 29/11): bản đồ nút thắt, E4, bắt đầu cải tiến

| Người | Việc | Phụ thuộc | Sản phẩm |
|---|---|---|---|
| 1 | Thêm vào `run-baseline.py`: lấy mẫu `pg_stat_activity` (chờ khóa), đếm deadlock, lấy mẫu `/actuator/prometheus`; cài nginx, Redis, script ghim CPU trên Kaggle; notebook chạy lần lượt nhiều tag trong một phiên. **E7:** nhánh từ `phase-1-baseline`, migration `booking_idempotency`, claim key bằng upsert native, hash payload, header và 2 mã lỗi; test chấp nhận 1, 2, 4–10 của ADR-006 | CP1 | Notebook chạy được A; nhánh E7 |
| 2 | Cập nhật `docs/phase-1/openapi.json` và README theo API mới; hoàn thiện seed `large`; viết S6 `overload.js`, S2 `hot-showtime.js`, seed `hot`; **phiên Kaggle thử nghiệm với A:** E4 trên dataset lớn trước, rồi S5, S6, S1–S4 mỗi mức 1 lượt | CP1; runner của Người 1 | "Bản đồ nút thắt của A" (1 trang, gửi cả nhóm) |
| 3 | Hoàn thiện S4; cập nhật [so-do-luong-nghiep-vu.md](../phase-1/so-do-luong-nghiep-vu.md) theo luồng hủy mới (Người 4 review); integration test B với hủy suất đồng thời; bắt đầu cache lịch chiếu và snapshot suất | CP1 | PR sơ đồ; PR cache Theater (nháp) |
| 4 | E4: `EXPLAIN` cho `GET /bookings` và `GET /admin/bookings`, sửa N+1 bằng batch fetch, test đếm số query; gắn tag `phase-2-query`. Sau đó triển khai phương án B từ `phase-1-baseline` | CP1 | Tag `phase-2-query`; nhánh B |

**CP2 (Chủ nhật 29/11): Bản đồ nút thắt**

- [ ] Có "bản đồ nút thắt của A" dựa trên số liệu Kaggle: điểm bão hòa đọc, hành vi quá tải, kết quả E4 trên dataset lớn, chạy thử S1–S4
- [ ] Chốt mức tải cho S2, S5, S6 và kích thước dataset `large`
- [ ] Tag `phase-2-query` pass `mvn verify`, có test đếm số query
- [ ] Nhánh B pass `mvn verify`, gồm test đặt vé đồng thời với hủy suất
- [ ] Notebook chạy được nhiều tag trong một phiên
- [ ] Nhánh E7 pass test chấp nhận 1, 2, 4–10 của ADR-006

Nếu không đạt: họp đánh giá lại khối lượng GĐ3. Nếu chưa có bản đồ nút thắt thì GĐ3 vẫn làm, nhưng giới hạn của E3 và các mức tải phải chốt muộn nhất thứ Tư 2/12.

### GĐ3 (30/11 – 6/12): hoàn thiện cải tiến, đóng băng code

| Người | Việc | Phụ thuộc | Sản phẩm |
|---|---|---|---|
| 1 | **Ưu tiên E7 trước E6:** test chấp nhận 3 (cùng key qua hai instance), gắn tag `phase-2-idempotency`. Sau đó cấu hình 2 instance + nginx; `compose.scale.yaml`, `compose.observability.yaml` + dashboard Grafana; review và gắn các tag | CP2 | Tag `phase-2-idempotency`; hạ tầng E6; tag `phase-2-*` |
| 2 | Hoàn thiện cache Movie (E2, sau khi có kết quả E4), profile Redis; script đo cửa sổ dữ liệu cũ; chạy thử cả ba tag E1 trên Kaggle | CP2 | Tag `phase-2-cache` (cùng Người 3) |
| 3 | Hoàn thiện cache Theater; filter rate limiting (E3), giới hạn chọn từ số liệu GĐ2; script tổng hợp so sánh nhiều phương án (CSV + biểu đồ) | CP2 | Tag `phase-2-ratelimit` |
| 4 | Phương án C; test deadlock với booking nhiều ghế chồng nhau; xác nhận E4 bằng `EXPLAIN (ANALYZE, BUFFERS)` và thêm index nếu số liệu chỉ ra; gắn lại `phase-2-query` nếu có thay đổi | CP2 | Tag `phase-2-share-lock`, `phase-2-seat-lock` |

**CP3 (Chủ nhật 6/12): Code freeze và điểm cắt**

- [ ] Mỗi thí nghiệm còn giữ có tag, pass `mvn verify` và có test tính đúng đi kèm (409, xóa cache, 429, số query)
- [ ] Tag `phase-2-idempotency` pass đủ 10 test chấp nhận của ADR-006
- [ ] Notebook đã chạy thử được mọi tag và cấu hình E6
- [ ] Script tổng hợp chạy được trên số liệu chạy thử
- [ ] Quyết định cắt theo mục 6: thí nghiệm nào chưa đạt các điều trên thì chuyển sang mục "hướng phát triển" của báo cáo

**Từ sau CP3 không sửa code đo.** Chỉ sửa bug làm sai tính đúng, và khi đó phải đo lại toàn bộ thí nghiệm liên quan.

### GĐ4 (7/12 – 13/12): đo chính thức

| Phiên Kaggle | Nội dung | Ước lượng |
|---|---|---|
| Phiên 1 (đầu tuần) | E1 thứ tự A→B→C; E4; E5 | khoảng 6,5 giờ |
| Phiên 2 | E1 thứ tự C→B→A; E2; E3; E7 | khoảng 6,5 giờ |
| Phiên 3 (cuối tuần) | E6; cấu hình tổng hợp `phase-2-final` so với A | khoảng 3,5 giờ |

| Người | Việc |
|---|---|
| 1 | Hỗ trợ môi trường trong lúc chạy; sau phiên 2, ghép các phương án được giữ thành `phase-2-final` |
| 2 | Chạy cả ba phiên; lưu evidence vào `docs/phase-2/performance/` |
| 3 | Tổng hợp số liệu ngay sau mỗi phiên, gửi bảng sơ bộ cho cả nhóm |
| 4 | Kiểm tra tính đúng trong DB sau mỗi lượt contention; điều tra bất thường |

**CP4 (Chủ nhật 13/12): Đủ số liệu**

- [ ] Mọi phương án còn giữ có đủ 3 lượt mỗi mức, đo trong cùng phiên với A
- [ ] Không phương án nào vi phạm tính đúng; nếu có, đã ghi nhận và loại
- [ ] Cấu hình tổng hợp `phase-2-final` đã đo so với A
- [ ] Evidence (CSV, `environment.json`, ZIP raw) đã commit, không có fixture chứa JWT

Nếu không đạt: thí nghiệm thiếu số liệu được báo cáo là "chưa đo đủ", không suy diễn. Phiên 3 có thể chạy bù sang thứ Hai 14/12, nhưng không muộn hơn.

### GĐ5 (14/12 – 20/12): phân tích và đóng gói, không thêm tính năng

| Người | Việc |
|---|---|
| 1 | Cập nhật README (kiến trúc Pha 2, cách chạy compose demo); chạy Docker trên máy sạch; mục observability của báo cáo; gắn tag `phase-2-final` |
| 2 | Viết báo cáo Pha 2: giả thuyết, số liệu, kết luận, trade-off; chốt ADR-003 |
| 3 | Biểu đồ; chốt ADR-004; review báo cáo |
| 4 | Chốt ADR-002, ADR-005 và ADR-006 kèm số liệu; merge các phương án được giữ vào `main` |

**CP5 (Chủ nhật 20/12): Nộp**

- [ ] Báo cáo Pha 2 hoàn chỉnh, mỗi thí nghiệm có bảng, biểu đồ, kết luận và trade-off
- [ ] ADR-002 đến ADR-006 ở trạng thái Accepted hoặc Rejected kèm số liệu
- [ ] `main` chứa các phương án được giữ, `mvn verify` pass, Docker chạy được trên máy sạch
- [ ] Tag `phase-2-final`

## 5. Sản phẩm cuối Pha 2

- Booking hoàn chỉnh theo SPEC: hủy mềm, quy tắc 30 phút, mã booking, Admin xem mọi booking, phân trang; idempotency cho `POST /bookings`; OpenAPI và sơ đồ luồng nghiệp vụ cập nhật theo.
- Báo cáo Pha 2 trong `docs/phase-2/` kèm bảng so sánh và biểu đồ của từng thí nghiệm.
- ADR-002 đến ADR-006 đã chốt.
- Evidence: CSV tổng hợp, `environment.json`, ZIP raw k6 của mỗi phiên.
- Script k6 S2, S4, S5, S6, seed `hot` và `large`, notebook Kaggle Pha 2.
- `compose.scale.yaml`, `compose.observability.yaml`, dashboard Grafana.
- Code các phương án được giữ đã merge vào `main`, `mvn verify` pass.

## 6. Ưu tiên và điểm cắt

| Mức | Thí nghiệm | Khi bị trễ |
|---|---|---|
| Bắt buộc | Bước 0 (0.1–0.11), E1, E4, E5, E7 (code và test tính đúng) | Không cắt; dồn người từ mức thấp hơn sang |
| Nên có | E2, E3, phần đo overhead của E7 | Cắt ở CP3 nếu chưa có tag; overhead E7 cắt được ở GĐ4 nếu thiếu thời gian Kaggle |
| Có thể bỏ | E6 | Cắt đầu tiên (Người 1 ưu tiên E7 trước E6), có thể cắt sớm ngay ở CP1 nếu Bước 0 trễ; vẫn giữ `compose.scale.yaml` làm demo nếu đã có |

## 7. Quy tắc làm việc

- Mỗi người chỉ sửa package, migration và cache của module mình (ngoại lệ ghi ở mục 2); thay đổi `pom.xml`, `config`, `shared` phải được Người 1 duyệt.
- Interface giữa các module merge sớm; module phụ thuộc dùng fake hoặc mock, không chờ.
- Branch ngắn, merge tối đa 2 ngày một lần; mỗi phương án đo là một tag, không đo từ code chưa commit.
- Mỗi người tự viết test cho phần mình; mỗi cải tiến phải có test tính đúng đi kèm.
- Không nới ngưỡng hay giảm tải để làm đẹp kết quả; chưa đo thì ghi "chưa đo".
- Ai bị chặn quá nửa ngày thì báo ngay trong nhóm, không chờ đến checkpoint.

## 8. Kết quả checkpoint

| Checkpoint | Ngày họp | Kết quả | Ghi chú, việc dời sang giai đoạn sau |
|---|---|---|---|
| CP1a | | | |
| CP1 | | | |
| CP2 | | | |
| CP3 | | | |
| CP4 | | | |
| CP5 | | | |
