Thực hiện chuẩn xác 3 tác vụ sau vào các file trong `app/src/main/kotlin/.../liveupdate/`:

1. TẠO TẤM KHIÊN BẢO VỆ 3 GIÂY (trong LiveUpdateNotifier.kt):
   - Thêm vào scope toàn cục của object/class: 
     `private val lastLocalReplyAtByMirrorKey = java.util.concurrent.ConcurrentHashMap<String, Long>()`
     `private const val LOCAL_REPLY_GRACE_MS = 3_000L`
   - Bổ sung hàm:
     `fun stampLocalReply(mirrorKey: String) { lastLocalReplyAtByMirrorKey[mirrorKey] = android.os.SystemClock.elapsedRealtime() }`
   - Bổ sung hàm:
     `fun isWithinReplyGrace(mirrorKey: String): Boolean { val t = lastLocalReplyAtByMirrorKey[mirrorKey] ?: return false; return (android.os.SystemClock.elapsedRealtime() - t) < LOCAL_REPLY_GRACE_MS }`
   - Gọi `stampLocalReply(mirrorKey)` ngay sau khi lưu vào `replyHistoryByMirrorKey` trong hàm `addLocalEchoAndRefresh`.

2. BẢO VỆ CACHE & SỬA LISTENER (trong LiveUpdateNotificationListenerService.kt):
   - Dùng tool search TẤT CẢ các lệnh `replyHistoryByMirrorKey.remove` hoặc `conversationHistoryCache.remove` đang bị gọi trong luồng cancel/removed của app gốc và XÓA BỎ CHÚNG. Cache phải được giữ lại để gộp.
   - Trong hàm xử lý sự kiện `onNotificationRemoved` hoặc các hàm cancel: Thêm logic kiểm tra `if (LiveUpdateNotifier.isWithinReplyGrace(mk)) return` để chặn việc xóa. 
   - TUYỆT ĐỐI KHÔNG CHẶN ở hàm `onNotificationPosted` (update thông báo mới thì vẫn phải cho chạy qua để gộp).

3. PIPELINE GỘP LỊCH SỬ (trong LiveUpdateNotifier.kt):
   - Xây dựng hàm `mergeForMirror(mirrorKey: String, sourceMessages: List<Message>): List<Message>` ngay trên hàm `buildMirroredNotification`.
   - Logic của hàm:
     + Lấy tin nhắn từ `replyHistoryByMirrorKey[mirrorKey]`.
     + Viết logic `deepCopy` chép toàn bộ (text, timestamp, dataMimeType, dataUri, extras).
     + Nếu tin nhắn đến từ cache (của mình gửi) -> bắt buộc set `Person = null` trong bản copy.
     + Gộp tin nhắn từ sourceMessages và cache lại.
     + Lọc trùng (distinct) bằng `text.toString().trim().lowercase()` + timestamp.
     + Sắp xếp (sortedBy) tăng dần theo timestamp và trả về list.
   - Trong `buildMirroredNotification`, thay vì add thẳng tin nhắn gốc vào Style, hãy dùng list `merged` từ hàm trên để add vào `MessagingStyle`.

Hãy tự tin phân tích file, tìm đúng vị trí và thực hiện `edit_file` một cách cẩn thận, đảm bảo null-safe và không làm vỡ các logic hiện tại. Báo cáo lại cho tôi những hàm bạn đã sửa.