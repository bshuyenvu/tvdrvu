# theVũ TV — Android

## Phiên bản 3.4.2

- Tự nhận diện track phụ đề của từng luồng phát.
- Chỉ hiện nút **CC** với kênh có phụ đề được thiết bị hỗ trợ.
- Tự ẩn **CC** khi kênh không có phụ đề và kiểm tra lại khi đổi nguồn dự phòng.
- Build chính thức: Debug và Release qua GitHub Actions.

## Cập nhật v3.4.1

- Chuẩn hóa toàn bộ tên ứng dụng thành **theVũ TV**.
- Logo hiển thị liền mạch **theVũTV**, giữ đúng chữ hoa/thường và dấu tiếng Việt.

## Cập nhật v3.4.0

- Đổi nhận diện ứng dụng và bổ sung logo chữ liền mạch hai màu.
- Đồng bộ giao diện xanh đen, cyan và vàng cam theo nhận diện mới.
- Thêm hướng dẫn sử dụng chỉ hiển thị một lần sau lần cài đặt đầu tiên.

## Cập nhật v3.3.1

- Giữ màn hình luôn sáng khi kênh đang phát ở khung nhỏ, toàn màn hình hoặc Picture-in-Picture.
- Timeout của Android không tự tắt màn hình trong lúc xem; người dùng vẫn có thể tắt bằng phím nguồn.
- Khi dừng phát hoặc đóng trình phát, ứng dụng trả lại chế độ tự tắt màn hình bình thường.

## Cập nhật v3.3.0

- Nút phụ đề trên trình phát: **CC TẮT → CC GỐC → 🌐 TIẾNG VIỆT**.
- Đọc phụ đề/closed-caption có sẵn trong luồng bằng Media3.
- Tự nhận diện ngôn ngữ và dịch sang tiếng Việt ngay trên thiết bị bằng Google ML Kit.
- Gói ngôn ngữ được tải khi dùng lần đầu; lựa chọn được lưu riêng theo từng kênh.
- Nếu kênh không phát kèm phụ đề, ứng dụng thông báo rõ thay vì hiển thị phụ đề giả.

## Cập nhật v3.2.0

- Lịch phát sóng 3 ngày: **Hôm qua • Hôm nay • Ngày mai**.
- Có nút **PHÁT LẠI** cho chương trình đã phát nếu nguồn M3U có khai báo `catchup-source` hợp lệ.
- Luồng lỗi được tự thử lại tối đa 2 lần rồi tự chuyển sang nguồn dự phòng kế tiếp.
- Không hiện ngay bảng lỗi đỏ trong lúc trình phát còn đang tự phục hồi.
- Nút **THỬ LẠI** khởi động lại từ nguồn chính.
- Timeout nguồn chết được rút ngắn để chuyển dự phòng nhanh hơn.



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

## Cập nhật v3.1.0

**Toàn màn hình và xoay màn hình (viết lại)**
- Toàn màn hình nay nằm ngay trên cửa sổ chính (không còn dùng hộp thoại), nên thanh hệ thống ẩn đúng và màn hình luôn sáng khi xem.
- Xoay điện thoại ngang: tự vào toàn màn hình; xoay dọc: tự thoát. Bấm nút toàn màn hình: khóa ngang.
- Thoát toàn màn hình: giữ chế độ dọc cho tới khi bạn thật sự cầm điện thoại dọc, rồi trả lại tự xoay
  (sửa lỗi kẹt chế độ dọc, xoay lần sau không ăn).
- Vuốt dọc **nửa trái** chỉnh độ sáng, **nửa phải** chỉnh âm lượng; chạm nhẹ ở bất kỳ đâu vẫn hiện/ẩn thanh điều khiển.
  Thoát toàn màn hình thì độ sáng trả về theo hệ thống.

**Lịch phát sóng từng kênh**
- Nút LỊCH PHÁT SÓNG mở danh sách chương trình theo ngày, đánh dấu chương trình đang phát và tự cuộn tới đó.
- Dòng "Đang phát: …" hiện ngay dưới tên kênh.
- Dữ liệu từ dịch vụ EPG Việt Nam (lichphatsong.io.vn); ghép được khoảng 75/80 kênh trong danh sách VN.

**Luồng dự phòng**
- Các mục cùng kênh được gộp thành một kênh nhiều nguồn (VTV1–VTV9 có 4–6 nguồn); danh sách không còn lặp kênh.
- Nguồn bị chặn vùng (Geo-blocked) không còn bị ẩn, mà xếp cuối làm dự phòng. 8 kênh trước đây mất hẳn vì chỉ có nguồn loại này.
- Luồng lỗi thì dịch vụ phát tự chuyển sang nguồn kế tiếp (kể cả khi app ở nền). Nút ĐỔI NGUỒN để chuyển tay.
- Thêm danh sách tiếng Việt của iptv-org làm nguồn bổ sung.
- Menu Tiện ích → **Nguồn phát dự phòng của bạn…**: dán liên kết M3U riêng; kênh trùng tên được gộp làm nguồn dự phòng.
- Trình phát dùng user-agent như trình duyệt và cho phép chuyển hướng http↔https (nguyên nhân phổ biến khiến một số luồng không phát).

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
