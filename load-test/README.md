# Chạy k6 cho Cinema API

Các kịch bản gọi API đang chạy; chúng không tự khởi động ứng dụng hay PostgreSQL. Theo `docs/phase-1/SPEC.md` mục 16, workload hỗn hợp và tranh ghế cần Booking API đã tích hợp theo contract. Script không giả lập thành công khi server chưa có các route đó.

## Chuẩn bị

Dùng môi trường đo riêng. Seed tạo phim, phòng, suất chiếu và user tải; nó không xóa dữ liệu hiện có. Fixture local chứa JWT nên đã được ignore, không commit hoặc chia sẻ file này.

Cần Node.js 18+, k6, Java và PostgreSQL. Trên Kaggle, khởi động PostgreSQL và Spring Boot theo hướng dẫn môi trường trước. Tạo một Admin từ biến `DEMO_ADMIN_USERNAME`/`DEMO_ADMIN_PASSWORD`, rồi:

~~~bash
export BASE_URL=http://127.0.0.1:8080
export ADMIN_USERNAME=admin
export ADMIN_PASSWORD='mat-khau-local'
export MOVIE_COUNT=1000
export DELETED_COUNT=50
export SEED_SCOPE=all
export VUS=50
node load-test/seed.mjs
export FIXTURES="$(pwd)/load-test/fixtures.local.json"
~~~

Có thể đặt `ADMIN_TOKEN` thay cho username/password. Seed đầy đủ tạo fixture cho tối đa 50 VU: hai nhóm user/ghế cho warm-up và đo, cùng chín ghế contention riêng. Token hết hạn sau một giờ; seed lại hoặc chạy `node load-test/refresh-tokens.mjs` để cấp token mới.

## Smoke và tải đọc Movie

`smoke.js` tạo, đọc Public, sửa, kiểm tra validation và xóa mềm một phim. `movie-read.js` dùng 50% GET danh sách và 50% GET chi tiết, không gửi JWT cho GET Movie.

~~~bash
k6 run --include-system-env-vars load-test/smoke.js
k6 run --include-system-env-vars load-test/movie-read.js
~~~

Lượt thử nhanh có thể đặt `MOVIE_COUNT=5`, `DELETED_COUNT=0`, `WARMUP_SECONDS=0`, `DURATION_SECONDS=5`; không dùng lượt này làm baseline.

## Tải hỗn hợp Booking

`booking.js` lặp tỷ trọng đúng theo SPEC: 35% danh sách phim, 25% lịch chiếu, 25% trạng thái ghế, 10% POST booking và 5% danh sách booking của user. Mỗi user dùng ghế riêng; các booking được hủy trước lượt đặt kế tiếp và khi teardown. Cleanup tạo thêm request ngoài tỷ trọng workload, nên phải ghi nhận khi phân tích.

Booking hiện trả các booking của user đã đăng nhập tại `GET /api/v1/bookings`; kịch bản dùng đúng route này và hủy các booking của tài khoản fixture chuyên dụng:

~~~bash
k6 run --include-system-env-vars load-test/booking.js
~~~

## Tranh ghế

Mỗi lượt dùng nhiều user tranh đúng một ghế: kỳ vọng một 201 và N−1 lỗi 409 có mã `SEAT_ALREADY_BOOKED`. Dùng `CONTENTION_RUN_INDEX` khác nhau từ 1 tới 9 để mỗi lượt có ghế mới. Giữ booking thắng lại để đối chiếu PostgreSQL bằng `verify-contention.sql`; sau khi đã lưu bằng chứng mới dọn dữ liệu lượt thử.

~~~bash
export VUS=10
export CONTENTION_RUN_INDEX=1
k6 run --include-system-env-vars load-test/contention.js
~~~

Chạy ma trận 10/25/50 VU, ba lượt mỗi mức, với index 1–9. Không chạy lại cùng index trước khi hoàn tất vì ghế của lượt thắng vẫn được giữ.

## Baseline lặp và tài nguyên trên Kaggle/Linux

Commit code, seed script và k6 trước khi đo. Tạo fixture một lần với `VUS=50`; không seed lại giữa các lượt booking. Truyền PID tiến trình Java/PostgreSQL để runner lấy CPU/RSS của API, DB và chính k6:

~~~bash
python load-test/run-baseline.py --scenario booking --api-pid "$API_PID" --database-pid "$DATABASE_PID"
python load-test/run-baseline.py --scenario contention --api-pid "$API_PID" --database-pid "$DATABASE_PID"
~~~

Mặc định chạy 10/25/50 VU, ba lượt mỗi mức, warm-up 30 giây và đo 120 giây cho Movie/Booking. Contention là batch một lần/VU, dùng chín ghế fixture riêng. Kết quả raw k6, summary, log, CSV tài nguyên và `environment.json` được lưu trong `load-test/results/` (không commit raw result nếu có credential hoặc dữ liệu nhạy cảm).

Ghi cùng commit, dataset, CPU/vCPU/quota, RAM, Java, PostgreSQL, k6, JVM flags, pool, thời lượng, warm-up và think time. Throughput lấy từ `measured_requests / 120`; lọc CPU/RSS theo timestamp phase `measure`, không lấy trung bình gồm warm-up. `summedRssMiB` có thể đếm trùng trang nhớ chia sẻ.

Trên Windows, dùng `run-baseline.ps1` với PID tiến trình Java. Nếu PostgreSQL chạy trong Docker, thêm `-DockerContainerNames btl-ktpm-db-1` để ghi `docker stats` theo giây vào CSV riêng. Khi chạy k6 thủ công, thêm `--include-system-env-vars` để script nhận fixture, URL và mức VU từ shell.

### Theo dõi PostgreSQL và Prometheus trên Kaggle

Runner Python mặc định lấy thêm ba nhóm bằng chứng trong mỗi lượt chạy:

- `*-postgres.csv`: mỗi giây ghi số backend chờ lock, số connection active và `idle in transaction` từ `pg_stat_activity`.
- `*-deadlocks.json`: bộ đếm `pg_stat_database.deadlocks` trước/sau và chênh lệch của lượt chạy.
- `*-prometheus.txt`: raw snapshot của `/actuator/prometheus` mỗi 5 giây.

Runner gọi CLI `psql`; trên Kaggle CLI này đã được cài cùng PostgreSQL. Cấu hình kết nối bằng biến chuẩn của PostgreSQL. Có thể dùng trực tiếp các biến `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` của ứng dụng; runner tự đọc JDBC URL, hoặc khai báo rõ:

~~~bash
export PGHOST=127.0.0.1
export PGPORT=5432
export PGDATABASE=cinema_load
export PGUSER=cinema_load
export PGPASSWORD='mat-khau-local'
export PROMETHEUS_URL=http://127.0.0.1:8080/actuator/prometheus

python load-test/run-baseline.py --scenario booking \
  --api-pid "$API_PID" --database-pid "$DATABASE_PID"
~~~

Password không được ghi vào `environment.json`. Có thể đổi chu kỳ bằng `--postgres-interval`, `--prometheus-interval` và timeout bằng `--monitor-timeout`. Chỉ dùng `--skip-postgres` hoặc `--skip-prometheus` cho lượt chẩn đoán không dùng làm evidence chính thức.

Các file monitor có timestamp UTC. Khi tổng hợp, đối chiếu timestamp với raw k6 có tag `phase=measure`; runner không tự suy đoán ranh giới pha vì `setup()` của từng kịch bản có thời lượng khác nhau.

Điền [mẫu báo cáo](../docs/phase-1/bao-cao-hieu-nang-mau.md) sau ba lượt mỗi mức. Báo p50/p95/p99, throughput, lỗi kỹ thuật, lỗi nghiệp vụ và tài nguyên; với contention ghi số winner, conflict đúng mã và xác nhận DB không có ghế bị giữ trùng. Chưa chạy thì để là chưa đo.
