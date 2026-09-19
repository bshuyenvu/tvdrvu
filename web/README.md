# theVũ TV — Web

Webapp dựa trên theVũ TV Android v3.5.0. Chạy bằng Next.js trong thư mục `web/`.

```bash
npm ci
npm run build
npm run dev
```

Trên Vercel, đặt **Root Directory** là `web` và framework là Next.js. Danh sách kênh và lịch phát sóng lấy từ nguồn công khai qua hai API trong ứng dụng; trình duyệt tự phát HLS khi nguồn cho phép CORS. Kênh bị chặn vùng hoặc không cho trình duyệt truy cập có thể mở bằng VLC.

Có tìm kiếm, nhóm kênh, yêu thích, lịch sử, nhiều nguồn phát, lịch phát sóng ba ngày, tệp nhắc xem `.ics`, PWA và nút CC chỉ hiện sau khi trình phát đọc được cue phụ đề. Nhắc xem qua `.ics` cần được nhập vào lịch thiết bị. Dịch phụ đề trên máy bằng ML Kit và ghi hình nền của Android không áp dụng trong trình duyệt.
