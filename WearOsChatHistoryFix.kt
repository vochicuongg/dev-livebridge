package com.kakao.taxi.liveupdate

import android.graphics.Typeface
import android.service.notification.StatusBarNotification
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * ============================================================
 * FIX: Khôi phục Lịch sử Chat trong BigTextStyle cho Wear OS
 * ============================================================
 * 
 * Giải quyết vấn đề: BigTextStyle chỉ hiển thị tin nhắn mới nhất,
 * mất đi lịch sử trò chuyện 5-7 tin nhắn gần đây.
 * 
 * Solution: Trích xuất đầy đủ messages từ MessagingStyle và xây dựng
 * chuỗi chat history với format đẹp (Bold sender name + double newline).
 */

// ============================================================
// OPTIONAL: Bộ đệm cục bộ cho apps không cung cấp lịch sử
// ============================================================

/**
 * Cache lưu tạm lịch sử tin nhắn cho mỗi conversation.
 * Key: sbn.key (notification key)
 * Value: Danh sách tin nhắn (giới hạn 7 tin gần nhất)
 */
private val chatHistoryBuffer = ConcurrentHashMap<String, MutableList<MessageSnapshot>>()

/**
 * Data class đại diện cho một tin nhắn đã snapshot
 */
private data class MessageSnapshot(
    val senderName: String,
    val text: CharSequence,
    val timestamp: Long
)

/**
 * Số lượng tin nhắn tối đa hiển thị trên Wear OS
 */
private const val MAX_CHAT_HISTORY_MESSAGES = 7

// ============================================================
// MAIN FIX: Xây dựng Chat History với Bold Sender Names
// ============================================================

/**
 * Xây dựng chuỗi lịch sử chat từ MessagingStyle với format đẹp.
 * 
 * Format kết quả:
 * ```
 * Hùng: Đi cafe không?
 * 
 * Bạn: Đợi xíu đang thay đồ.
 * ```
 * (Tên người gửi được IN ĐẬM)
 * 
 * @param messagingStyle MessagingStyle đã extract từ notification gốc
 * @param sbn StatusBarNotification (dùng cho buffer fallback)
 * @return SpannableStringBuilder chứa lịch sử chat đã format
 */
private fun buildChatHistory(
    messagingStyle: NotificationCompat.MessagingStyle,
    sbn: StatusBarNotification
): CharSequence {
    val chatHistory = SpannableStringBuilder()
    
    // ========================================
    // BƯỚC 1: Lấy tin nhắn từ MessagingStyle
    // ========================================
    
    val allMessages = messagingStyle.messages
    
    if (allMessages.isNotEmpty()) {
        // App gốc cung cấp lịch sử - lấy 7 tin nhắn gần nhất
        val recentMessages = if (allMessages.size > MAX_CHAT_HISTORY_MESSAGES) {
            allMessages.takeLast(MAX_CHAT_HISTORY_MESSAGES)
        } else {
            allMessages
        }
        
        // Cập nhật buffer cho lần sau (optional)
        updateChatHistoryBuffer(sbn.key, recentMessages)
        
        // Xây dựng chuỗi chat history
        recentMessages.forEachIndexed { index, message ->
            appendMessageToHistory(
                chatHistory = chatHistory,
                senderName = extractSenderName(message, messagingStyle),
                messageText = message.text ?: "",
                isFirstMessage = index == 0
            )
        }
        
    } else {
        // ========================================
        // FALLBACK: Sử dụng buffer nếu app không cung cấp lịch sử
        // ========================================
        
        // Trường hợp app gốc chỉ gửi 1 tin nhắn và xóa lịch sử
        val bufferedMessages = chatHistoryBuffer[sbn.key]
        if (!bufferedMessages.isNullOrEmpty()) {
            bufferedMessages.forEachIndexed { index, snapshot ->
                appendMessageToHistory(
                    chatHistory = chatHistory,
                    senderName = snapshot.senderName,
                    messageText = snapshot.text,
                    isFirstMessage = index == 0
                )
            }
        } else {
            // Không có lịch sử - chỉ hiển thị thông tin cơ bản
            chatHistory.append("No message history available")
        }
    }
    
    return chatHistory.trim()
}

/**
 * Thêm một tin nhắn vào chuỗi chat history với format đẹp.
 * 
 * @param chatHistory SpannableStringBuilder để append vào
 * @param senderName Tên người gửi (sẽ được IN ĐẬM)
 * @param messageText Nội dung tin nhắn
 * @param isFirstMessage Có phải tin nhắn đầu tiên không (để xử lý newline)
 */
private fun appendMessageToHistory(
    chatHistory: SpannableStringBuilder,
    senderName: String,
    messageText: CharSequence,
    isFirstMessage: Boolean
) {
    // Thêm dấu xuống dòng kép giữa các tin nhắn (trừ tin đầu tiên)
    if (!isFirstMessage) {
        chatHistory.append("\n\n")
    }
    
    // Vị trí bắt đầu của tên người gửi (để apply bold span)
    val senderStartPos = chatHistory.length
    
    // Thêm tên người gửi
    chatHistory.append(senderName)
    
    // Vị trí kết thúc của tên người gửi
    val senderEndPos = chatHistory.length
    
    // Apply BOLD style cho tên người gửi
    chatHistory.setSpan(
        StyleSpan(Typeface.BOLD),
        senderStartPos,
        senderEndPos,
        SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
    )
    
    // Thêm dấu hai chấm và nội dung tin nhắn
    chatHistory.append(": ")
    chatHistory.append(messageText)
}

/**
 * Trích xuất tên người gửi từ Message.
 * 
 * @param message Message cần extract
 * @param messagingStyle MessagingStyle để fallback lấy user name
 * @return Tên người gửi (không null)
 */
private fun extractSenderName(
    message: NotificationCompat.MessagingStyle.Message,
    messagingStyle: NotificationCompat.MessagingStyle
): String {
    // Priority 1: Person name từ message
    message.person?.name?.toString()?.let { return it }
    
    // Priority 2: User name từ MessagingStyle (cho tin nhắn của chính mình)
    messagingStyle.user?.name?.toString()?.let { return it }
    
    // Fallback: Unknown sender
    return "Unknown"
}

/**
 * Cập nhật buffer lưu tạm lịch sử chat (optional - cho fallback).
 * 
 * @param notificationKey Key của notification (sbn.key)
 * @param messages Danh sách tin nhắn mới
 */
private fun updateChatHistoryBuffer(
    notificationKey: String,
    messages: List<NotificationCompat.MessagingStyle.Message>
) {
    try {
        val snapshots = messages.map { msg ->
            MessageSnapshot(
                senderName = msg.person?.name?.toString() ?: "Unknown",
                text = msg.text ?: "",
                timestamp = msg.timestamp
            )
        }
        
        // Lưu vào buffer (giới hạn 7 tin nhắn)
        val limitedSnapshots = if (snapshots.size > MAX_CHAT_HISTORY_MESSAGES) {
            snapshots.takeLast(MAX_CHAT_HISTORY_MESSAGES).toMutableList()
        } else {
            snapshots.toMutableList()
        }
        
        chatHistoryBuffer[notificationKey] = limitedSnapshots
        
    } catch (e: Exception) {
        android.util.Log.w("LiveUpdateNotifier", "Failed to update chat history buffer", e)
    }
}

/**
 * Xóa buffer khi notification bị dismiss (gọi khi cần cleanup).
 * 
 * @param notificationKey Key của notification cần xóa
 */
private fun clearChatHistoryBuffer(notificationKey: String) {
    chatHistoryBuffer.remove(notificationKey)
}

// ============================================================
// INTEGRATION: Cách sử dụng trong applyWearOsSourcePresentation
// ============================================================

/**
 * EXAMPLE: Tích hợp vào hàm applyWearOsSourcePresentation
 * 
 * Thay thế đoạn code xây dựng chatHistory cũ bằng:
 */
private fun applyWearOsSourcePresentationExample(
    context: android.content.Context,
    builder: NotificationCompat.Builder,
    sbn: StatusBarNotification,
    sourceLargeIcon: android.graphics.Bitmap?
) {
    val source = sbn.notification
    val sourcePackageName = sbn.packageName
    
    // ... (code lấy app name và avatar - giữ nguyên)
    
    // ========================================
    // PHẦN THAY ĐỔI: Xử lý Chat History
    // ========================================
    
    try {
        val messagingStyle = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(source)
        
        if (messagingStyle != null) {
            // --- MESSAGING NOTIFICATION ---
            
            val appNameStr = "Zalo" // hoặc resolve từ sourcePackageName
            val conversationTitle = messagingStyle.conversationTitle?.toString() ?: ""
            val userName = messagingStyle.user?.name?.toString() ?: ""
            
            // Set Title
            val titleText = if (conversationTitle.isNotEmpty()) {
                "[$appNameStr] $conversationTitle"
            } else if (userName.isNotEmpty()) {
                "[$appNameStr] $userName"
            } else {
                "[$appNameStr]"
            }
            builder.setContentTitle(titleText)
            
            // ⭐ BUILD CHAT HISTORY với Bold Sender Names
            val chatHistory = buildChatHistory(messagingStyle, sbn)
            
            // Set BigTextStyle
            builder.setStyle(
                NotificationCompat.BigTextStyle().bigText(chatHistory)
            )
            
        } else {
            // --- NON-MESSAGING NOTIFICATION ---
            // ... (xử lý thông báo thường - giữ nguyên)
        }
        
    } catch (e: Exception) {
        android.util.Log.w("LiveUpdateNotifier", "Failed to build chat history", e)
    }
    
    // ... (code extract Reply action, set avatar, etc. - giữ nguyên)
}

// ============================================================
// CLEANUP: Gọi khi notification dismissed
// ============================================================

/**
 * Cleanup function - gọi khi user dismiss notification.
 * Xóa buffer để tránh memory leak.
 */
fun onNotificationDismissed(notificationKey: String) {
    clearChatHistoryBuffer(notificationKey)
}
