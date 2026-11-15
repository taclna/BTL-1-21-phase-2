# ADR-005: Mở rộng theo chiều ngang

- Trạng thái: Proposed (chốt sau khi có số liệu E6)
- Ngày: 2026-11-15
- Liên quan: [kế hoạch Pha 2 mục 10](../ke-hoach.md#10-e6-nhiều-replica), [ADR-003](003-cache-doc.md), [ADR-004](004-rate-limiting.md)

## Bối cảnh

Hệ thống là một modular monolith triển khai một instance ([ADR-001](../../phase-1/adr/001-modular-monolith-3-tier.md)). API stateless (JWT, không session), nên về nguyên tắc có thể chạy nhiều instance sau một load balancer mà không tách microservice. Tính đúng khi đặt ghế dựa vào khóa và constraint ở PostgreSQL, nên không phụ thuộc vào số instance.

Môi trường đo là một máy Kaggle 4 vCPU, không có Docker. Thêm instance trên cùng máy chỉ có ý nghĩa khi mỗi instance được gán core riêng.

## Phương án

| | Phương án | Ưu | Nhược |
|---|---|---|---|
| 1 | Một instance (hiện tại) | Đơn giản nhất | Không chịu lỗi; công suất giới hạn ở một tiến trình |
| 2 | Hai instance + nginx, cache Caffeine riêng mỗi instance | Tăng công suất, chịu được một instance chết | Dữ liệu cũ giữa các instance đến khi hết TTL; giới hạn tải (ADR-004) tính theo từng instance |
| 3 | Hai instance + nginx + Redis dùng chung cho cache | Không có dữ liệu cũ giữa các instance | Thêm Redis phải vận hành; thêm latency khi đọc cache |

Không xét: tách microservice (SPEC 3.3), autoscaling.

## Quyết định (tạm thời)

Đo cả ba phương án trong E6 với CPU được ghim (xem kế hoạch mục 10). Kết luận chỉ áp dụng cho câu hỏi "hệ thống có mở rộng được khi thêm tài nguyên không", không suy ra công suất production.

Ràng buộc:
- Các instance khởi động lần lượt để Flyway chỉ chạy migration một lần.
- Chạy S1 qua nginx ít nhất một lượt để xác nhận không bán trùng ghế khi có nhiều instance.

## Hệ quả

Viết sau khi có số liệu: hệ số tăng throughput khi thêm instance, cửa sổ dữ liệu cũ của phương án 2, chênh lệch p95 giữa phương án 2 và 3.
