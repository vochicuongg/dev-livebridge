Nhiệm vụ của bạn là sử dụng MCP Tools (truy cập `D:\livebridge`) để sửa lại hàm rebuild thông báo trong `LiveUpdateNotifier.kt` bằng chiến thuật "Rebuild Style từ đầu":

1. Gỡ bỏ hoàn toàn dòng `setRemoteInputHistory(arrayOf(echoMessage))`. (Tuyệt đối không dùng cái này khi đã dùng MessagingStyle).
2. Thay vì dùng `extractMessagingStyleFromNotification`, hãy tạo một instance `MessagingStyle` MỚI TINH: `val newStyle = NotificationCompat.MessagingStyle(userPerson)`. (Chú ý tên conversation title nếu có).
3. Duyệt qua TẤT CẢ tin nhắn trong danh sách cache (bao gồm cả tin nhắn cũ và tin nhắn vừa mới add vào). Thêm tuần tự từng tin nhắn vào `newStyle`. Nhớ quy tắc: tin của người dùng thì `person = null`.
4. Ép Builder xóa cache cũ bằng cách gọi `.setStyle(null)` trước, sau đó mới gọi `.setStyle(newStyle)`.
5. Kiểm tra kỹ biến ID truyền vào hàm `notificationManager.notify(id, builder.build())`. Hãy chắc chắn 100% ID này là kiểu `Int` và khớp chính xác với ID của thông báo đang hiển thị (ví dụ `mirrorIdForKey`).
6. Dùng tool `Edit file` để lưu lại những thay đổi này.

Hãy phân tích code hiện tại, thực hiện đúng các bước trên và báo lại cho tôi cấu trúc hàm sau khi đã tối ưu.