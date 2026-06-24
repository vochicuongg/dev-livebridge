Yêu cầu thực thi:
1. Hãy dùng tool `Read text file` hoặc `Search files` để đọc nội dung file `ReplyInterceptReceiver.kt`.
2. Phân tích logic trong hàm `onReceive()` hiện tại.
3. Cập nhật thêm đoạn code xử lý UI ngay sau khi lấy được nội dung tin nhắn (`RemoteInput`):
   - Trích xuất/Tìm lại thông báo đang active (gợi ý: sử dụng `NotificationManager.getActiveNotifications()` đối chiếu với Notification ID từ intent, hoặc lấy trực tiếp từ intent nếu có truyền).
   - Trích xuất `MessagingStyle` từ thông báo đó.
   - Tạo một đối tượng `Person` đại diện cho người dùng (người gửi) để tin nhắn tự động được căn lề phải.
   - Thêm nội dung vừa gõ vào `MessagingStyle` đó.
   - Sử dụng `NotificationManager.notify()` với ĐÚNG ID của thông báo cũ để cập nhật giao diện mà không làm mất thông báo.
4. Đảm bảo tính an toàn (null safety) và tương thích phiên bản Android.
5. Sau khi suy luận xong, hãy dùng tool `Edit file` hoặc `Write file` để cập nhật lại trực tiếp file `ReplyInterceptReceiver.kt`.

Hãy làm việc thật cẩn thận, giải thích ngắn gọn cách bạn lấy ID thông báo cũ trước khi ghi file nhé.