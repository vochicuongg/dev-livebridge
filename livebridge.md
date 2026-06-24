Nhiệm vụ của bạn là điều tra và khắc phục nguyên nhân sâu xa trong luồng Rebuild Notification. Hãy làm theo các bước sau:

1. Dùng tool `Search files` và `Read text file` để tìm và đọc nội dung file chứa `LiveUpdateNotifier` (đặc biệt là hàm `addLocalEchoAndRefresh` và `buildMirroredNotification`).
2. Phân tích cách `MessagingStyle` được tạo ra và cập nhật:
   - Kiểm tra `LOCAL_USER_ME`: Đối tượng `Person` này có được định nghĩa đồng nhất với `Person` dùng trong constructor của `MessagingStyle` không? (Gợi ý: Thử thay thế `person = LOCAL_USER_ME` bằng `person = null` khi gọi `addMessage()` cho tin nhắn gửi đi, Android thường tự hiểu null là Local User và căn lề phải).
   - Kiểm tra luồng dữ liệu (Data flow): Khi `addLocalEchoAndRefresh` đẩy tin nhắn vào cache, hàm `buildMirroredNotification` có thực sự LẤY tin nhắn từ cache đó để đưa vào `MessagingStyle` không? Hay nó vô tình lấy lại dữ liệu từ `Notification.extras` cũ?
   - Phương án dự phòng: Nếu `MessagingStyle` vẫn "lì lợm", hãy bổ sung thêm `setRemoteInputHistory(arrayOf(echoMessage))` vào builder như một cơ chế fallback mạnh mẽ của Android để hiển thị tin nhắn vừa reply.
3. Suy luận cẩn thận, tìm ra điểm đứt gãy và dùng tool `Edit file` để sửa trực tiếp lỗi trong `LiveUpdateNotifier.kt` (hoặc file tương ứng). 

Mục tiêu tối thượng: Ngay khi người dùng nhấn gửi, dòng tin nhắn phải lập tức xuất hiện một cách mượt mà ở bên phải thông báo. Trải nghiệm phải thật sự liền mạch! Hãy nói cho tôi biết bạn đã sửa ở dòng nào và logic thay đổi ra sao.