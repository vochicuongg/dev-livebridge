## Nhiệm vụ của bạn (Senior Android Expert)
Hãy refactor lại logic của hàm `applyWearOsSourcePresentation` trong `LiveUpdateNotifier.kt` theo các bước sau:

### 1. Lấy thông tin cơ bản:
- Lấy tên ứng dụng: `val appNameStr = resolvedSubText ?: resolveWearOsAppHeader(context, sourcePackageName)?.appName ?: "App"`
- Trích xuất Avatar: `val avatarBitmap = resolveAppLargeIconBitmap(context, sourcePackageName)`

### 2. Xử lý Nội dung (Không dùng MessagingStyle):
Kiểm tra xem thông báo gốc có phải là tin nhắn không (thông qua `NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(source)`).
- Nếu CÓ: 
  - Đặt Title: `builder.setContentTitle("[$appNameStr] ${style.conversationTitle ?: style.user.name}")`
  - Tạo `SpannableStringBuilder` duyệt qua tất cả `style.messages` để tạo lịch sử chat (Ví dụ: "Người gửi: Nội dung tin nhắn\n").
  - Gán vào builder: `builder.setStyle(NotificationCompat.BigTextStyle().bigText(chatHistory))`
- Nếu KHÔNG (thông báo thường):
  - Đặt Title: `builder.setContentTitle("[$appNameStr] ${originalTitle}")`
  - Giữ nguyên text gốc bằng BigTextStyle.

### 3. Trích xuất Nút Trả Lời (Quan trọng nhất):
- Lặp qua mảng `source.notification.actions` (hoặc dùng `NotificationCompat.getAction()`).
- Tìm `Action` nào có chứa `RemoteInput` (Nghĩa là `action.remoteInputs != null` và mảng không rỗng).
- Nếu tìm thấy, hãy clone Action đó hoặc add trực tiếp vào `builder.addAction(replyAction)`. (Nếu có WearableExtender, hãy add vào cả extender để tối ưu cho Wear OS).

### 4. Giữ lại Avatar:
- `builder.setLargeIcon(avatarBitmap)` hoặc `sourceLargeIcon`.

## Yêu cầu Output
- In ra đoạn code Kotlin thực hiện logic trên để tôi thay thế vào hàm `applyWearOsSourcePresentation`.
- Đảm bảo an toàn Null (Try-Catch nếu cần khi trích xuất Actions).