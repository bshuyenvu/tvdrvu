# theVũ TV — Web

Webapp dựa trên theVũ TV Android v3.5.0. Bản web 3.6.0 bổ sung lịch phát sóng ba ngày, đánh dấu chương trình đang chiếu, nhắc xem qua lịch thiết bị, phát lại nếu M3U khai báo nguồn catchup, ghi hình trực tiếp vào tệp tải về, cửa sổ nhỏ, hẹn giờ tắt, tự thử nguồn dự phòng và lưu danh sách kênh theo nhóm. Chạy bằng Next.js trong thư mục `web/`.

```bash
npm ci
npm run build
npm run dev
```

Trên Vercel, đặt **Root Directory** là `web` và framework là Next.js. Danh sách kênh và lịch phát sóng lấy từ nguồn công khai qua hai API trong ứng dụng; trình duyệt tự phát HLS khi nguồn cho phép CORS. Kênh bị chặn vùng hoặc không cho trình duyệt truy cập có thể mở bằng VLC.

Có tìm kiếm, nhóm kênh, yêu thích, lịch sử, nhiều nguồn phát, lịch phát sóng ba ngày, tệp nhắc xem `.ics`, PWA và nút CC chỉ hiện sau khi trình phát đọc được cue phụ đề. Nhắc xem qua `.ics` cần được nhập vào lịch thiết bị. Ghi hình sử dụng MediaRecorder khi luồng phát và trình duyệt cho phép, lưu tệp WebM/MP4 sau khi dừng; trang cần tiếp tục mở, tự dừng sau 30 phút hoặc 250 MB. Trình duyệt không thể duy trì ghi hình khi đóng tab như dịch vụ Android. Dịch phụ đề trên thiết bị bằng ML Kit chỉ có trên Android.
