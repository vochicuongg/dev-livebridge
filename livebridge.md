## Nhiệm vụ của bạn (Senior Android Expert)
Hãy cập nhật lại logic của nhánh `else` (Fallback cho thông báo thường) trong khối code build notification trên Wear OS (nơi kiểm tra `if (messagingStyle != null)`).

### Yêu cầu thuật toán cho nhánh `else`:
1. **Trích xuất Title và Text một cách an toàn:**
   - `val originalTitle = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE) ?: ""`
   - `val originalText = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT) ?: ""`
   - `val originalBigText = sbn.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: originalText`

2. **Gán vào Builder (Phải tuân thủ đúng bộ 3 hàm này):**
   - `builder.setContentTitle("[$appNameStr] $originalTitle")`
   - `builder.setContentText(originalText)`  // QUAN TRỌNG NHẤT: Cái này giúp hiện Text ở màn hình xem nhanh
   - `builder.setStyle(NotificationCompat.BigTextStyle().bigText(originalBigText))` // Cái này giúp hiện Text đầy đủ khi tap vào

## Yêu cầu Output
- Chỉ in ra đoạn code Kotlin của khối `if/else` xử lý Style (tập trung vào nhánh `else`).
- Code cần an toàn với Null và đảm bảo không làm vỡ tính năng `buildChatHistory` của MessagingStyle ở nhánh `if` bên trên.