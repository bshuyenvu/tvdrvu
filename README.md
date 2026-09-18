# TV Dr Vũ — Android

Ứng dụng Android gốc dùng Jetpack Compose và Media3 ExoPlayer.

## Tính năng

- Tự tải danh sách Việt Nam từ IPTV-org
- Phát HLS bằng Media3 ExoPlayer
- Tìm kiếm, yêu thích, kênh gần đây
- Giao diện Material 3 thích ứng điện thoại, máy tính bảng và Android TV
- Mở luồng bằng VLC khi cần
- Hỗ trợ điều khiển cảm ứng, bàn phím và remote TV

## Mở và build

1. Mở thư mục này bằng Android Studio.
2. Chờ Gradle Sync hoàn tất.
3. Chọn **Build > Build APK(s)**.

APK debug nằm tại `app/build/outputs/apk/debug/app-debug.apk`.

## Cập nhật v3.0.0

**Kiến trúc phát:** một trình phát duy nhất chạy trong `PlaybackService` (Media3 `MediaSessionService`); giao diện
điều khiển nó qua `MediaController`.

- Vào cửa sổ nhỏ (PiP), xoay ngang, toàn màn hình **không còn tải lại luồng**.
- **Nghe nền:** thoát app vẫn nghe tiếng; có thông báo điều khiển, nút trên tai nghe/màn hình khóa. Ở nền chỉ tải
  tiếng (tắt luồng hình) để tiết kiệm dữ liệu; mở lại app thì hình tự bật lại.
- Menu Tiện ích có mục **Tự mở cửa sổ nhỏ khi thoát app** (mặc định tắt). Đóng cửa sổ nhỏ thì dừng phát.
- Vuốt bỏ app khỏi danh sách gần đây thì dừng hẳn (kể cả hẹn giờ tắt).
- **Lịch nhắc bền vững:** lưu lại và tự đặt lại sau khi khởi động máy hoặc cập nhật app.
- Hẹn giờ tắt dừng đúng cả khi app đang ở nền.

**Nâng thư viện:** AGP 8.13, Gradle 8.13, Kotlin 2.2.21, Compose 1.11 (BOM 2026.04.01), Media3 1.10.0,
Activity Compose 1.13.0, Lifecycle 2.10.0, OkHttp 5.3.2. `compileSdk` 36; `targetSdk` giữ 35 có chủ ý
(targetSdk 36 bỏ qua `requestedOrientation` trên máy tính bảng/màn hình gập nên hỏng toàn màn hình ngang).

Chưa nâng lên Compose 1.12 vì bản này đòi `compileSdk 37` và AGP 9 (cần chuyển sang Kotlin tích hợp sẵn của AGP 9
và Gradle 9.1). Nên làm ở một bước riêng. Coil 2.7.0 giữ nguyên (Coil 3 đổi tên gói, cần sửa mã).

## Cập nhật v2.3.0

- Sửa lỗi biên dịch của v2.2 (thiếu `import` `Calendar`/`Locale`).
- Nhắc lịch: bấm thông báo sẽ mở đúng kênh đã hẹn.
- Hiển thị kết quả ghi hình (thành công/thất bại) ngay trong app.
- Thông báo trong app tự tắt; nút THỬ LẠI chỉ hiện khi lỗi tải danh sách kênh.
- Danh sách "Gần đây" giữ đúng thứ tự sau khi mở lại app; mở app vào lại kênh đang xem dở.
- Lưu cache danh sách kênh: mất mạng vẫn mở được danh sách gần nhất.
- Đọc M3U nhanh hơn và đúng hơn (tên kênh/nhóm có dấu phẩy).
- Tự phục hồi lỗi HLS trực tiếp "behind live window".
- CI build thêm bản release (nhẹ hơn, mượt hơn).

## Ký APK bằng khóa cố định (để cài đè không mất dữ liệu)

Mỗi lần GitHub Actions chạy, khóa debug được tạo mới nên APK các lần build có chữ ký khác nhau và Android
không cho cài đè. Tạo khóa một lần rồi lưu vào Secrets của repo:

```bash
keytool -genkeypair -v -keystore tvdrvu.jks -alias tvdrvu -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 tvdrvu.jks      # macOS: base64 -i tvdrvu.jks
```

Repo → Settings → Secrets and variables → Actions → New repository secret:
`KEYSTORE_BASE64` (chuỗi base64 ở trên), `KEYSTORE_PASSWORD`, `KEY_ALIAS` (= `tvdrvu`), `KEY_PASSWORD`.
Giữ file `tvdrvu.jks` ở nơi an toàn; không commit lên repo.
