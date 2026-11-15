# ADR-003: Cache dữ liệu đọc

- Trạng thái: Proposed (chốt sau khi có số liệu E2)
- Ngày: 2026-11-15
- Liên quan: [kế hoạch Pha 2 mục 6](../ke-hoach.md#6-e2-cache-đọc), [SPEC mục 17, 21](../../phase-1/SPEC.md)

## Bối cảnh

Pha 1 cố ý không cache để có baseline trung thực (SPEC 21). Phần lớn tải theo SPEC 16 là đọc: danh sách phim, lịch chiếu, trạng thái ghế. Phim, lịch chiếu và sơ đồ ghế ít thay đổi và chỉ Admin sửa được. Trạng thái ghế đã đặt thì đổi theo từng giao dịch.

Pha 1 chưa tìm ra điểm bão hòa. Bằng chứng cho quyết định này đến từ S5 ở tuần 2.

## Phương án

| | Phương án | Ưu | Nhược |
|---|---|---|---|
| 1 | Không cache (hiện tại) | Không có dữ liệu cũ, đơn giản | Mọi request đọc đều xuống DB |
| 2 | Caffeine trong tiến trình, qua Spring Cache | Nhanh nhất, không thêm hạ tầng | Mỗi instance có cache riêng; dữ liệu cũ giữa các instance (E6) |
| 3 | Redis dùng chung, qua Spring Cache | Nhất quán giữa các instance | Thêm hạ tầng, thêm một lượt đi qua mạng |

Phương án 2 và 3 dùng cùng annotation, chỉ khác `spring.cache.type`, nên chuyển đổi được mà không sửa code nghiệp vụ.

## Quyết định (tạm thời)

Đo phương án 2 so với 1 trong E2. Chỉ đo phương án 3 trong E6 vì nó chỉ có ý nghĩa khi có nhiều instance.

Ràng buộc:
- Chỉ cache dữ liệu phim, lịch chiếu và snapshot suất kèm sơ đồ ghế. Không cache ghế đã đặt và booking.
- Mỗi module cache và xóa cache dữ liệu của chính mình, ở tầng business, trên method ghi tương ứng.
- TTL làm lưới an toàn, không thay cho việc xóa cache khi ghi.
- Phải có test xác nhận dữ liệu mới hiển thị ngay sau khi Admin ghi (một instance).

## Hệ quả

Viết sau khi có số liệu: throughput bền vững tối đa, CPU của DB, hit ratio, cửa sổ dữ liệu cũ (E6).
