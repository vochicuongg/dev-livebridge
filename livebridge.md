Giải pháp: TỪ BỎ VIỆC REBUILD MESSAGING STYLE CHO TIN NHẮN PHẢN HỒI. Bắt buộc phải dùng cơ chế duy nhất mà OS cho phép: `setRemoteInputHistory()`.

Nhiệm vụ của bạn:
1. Dùng tool `Edit file` để chỉnh sửa hàm `addLocalEchoAndRefresh` trong `LiveUpdateNotifier.kt` (và các file liên quan nếu cần).
2. XÓA BỎ hoàn toàn khối logic duyệt mảng `allCachedMessages` và khởi tạo lại `NotificationCompat.MessagingStyle`. KHÔNG DÙNG `.setStyle(newStyle)` nữa.
3. Thay vì cố gắng vẽ lại bong bóng chat bằng MessagingStyle, hãy ép Android hiển thị tin nhắn vừa gõ bằng "cửa sau":
   - Lấy `NotificationCompat.Builder(context, activeNotification)` (để giữ nguyên giao diện hiện tại).
   - Gọi hàm `.setRemoteInputHistory(arrayOf(replyText))` trên Builder này. 
   - Đảm bảo gọi thêm `.setOnlyAlertOnce(true)`.
   - `notify()` lại đúng ID của activeNotification đó.
   (Lưu ý: Mảng lịch sử có thể chứa nhiều tin nhắn cũ nếu bạn quản lý được, nhưng ít nhất phải chứa `replyText` vừa gõ).

Đây là cách duy nhất hệ điều hành chấp nhận để hiển thị chữ "Đang trả lời..." hoặc dòng text vừa gõ ở dưới đáy thông báo trên màn hình khóa và đồng hồ. Hãy thực thi việc gỡ bỏ MessagingStyle rebuild và áp dụng cơ chế RemoteInputHistory.