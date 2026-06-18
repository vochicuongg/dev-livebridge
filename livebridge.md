## Nhiệm vụ của bạn (Senior Android Expert)
Hãy bổ sung một cơ chế Bộ đệm cục bộ (In-memory Cache) vào `LiveUpdateNotifier.kt` để LiveBridge TỰ GHI NHỚ lịch sử trò chuyện thay vì phụ thuộc vào app gốc.

### Yêu cầu thuật toán:
1. **Khai báo Cache (Biến toàn cục trong class/object):**
   Tạo một biến lưu trữ an toàn luồng (Thread-safe):
   `private val conversationHistoryCache = ConcurrentHashMap<String, MutableList<NotificationCompat.MessagingStyle.Message>>()`

2. **Cập nhật hàm `buildChatHistory`:**
   Sửa lại hàm `buildChatHistory` để nó nhận thêm `sourcePackageName` và `conversationTitle`.
   - **Tạo Cache Key:** `val threadKey = "${sourcePackageName}_${conversationTitle}"`
   - **Lấy danh sách cũ:** `val historyList = conversationHistoryCache.getOrPut(threadKey) { mutableListOf() }`
   - **Merge tin nhắn mới:** Duyệt qua `messagingStyle.messages` hiện tại, thêm vào `historyList` (Kiểm tra trùng lặp dựa trên `timestamp` và `text` để tránh bị lặp tin).
   - **Cắt tỉa (Trim):** Nếu `historyList.size > 7`, giữ lại 7 tin gần nhất: `val recentMessages = historyList.takeLast(7)`
   - **Lưu lại cache:** Cập nhật lại mảng mới vào `conversationHistoryCache`.
   - **Build Text:** Cuối cùng, dùng vòng lặp duyệt qua `recentMessages` (chứ KHÔNG PHẢI `messagingStyle.messages`) để dùng `SpannableStringBuilder` nối chữ (`appendMessageToHistory`) giống như logic cũ.

3. **Chống Memory Leak (Quan trọng):**
   Ở hàm `cancelMirrored()` hoặc `removeSource()` (khi thông báo thực sự bị người dùng xóa/đọc xong), hãy nhớ xóa key tương ứng trong `conversationHistoryCache` để giải phóng RAM.

## Yêu cầu Output
- In ra đoạn code khai báo biến `conversationHistoryCache`.
- In ra đoạn code được viết lại của hàm `buildChatHistory` với logic lưu và gộp lịch sử.
- (Tùy chọn) Helper check trùng lặp (Dedup) tin nhắn dựa vào nội dung + thời gian.