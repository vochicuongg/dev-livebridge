# Hướng dẫn Triển khai: Fix Bug Giao diện Wear OS với SpannableString

## Tổng quan
Tài liệu này hướng dẫn cách refactor luồng xử lý văn bản trong `applyWearOsSourcePresentation` để hiển thị tên ứng dụng dưới dạng **subtitle** (chữ nhỏ, nằm dưới tên người gửi) thay vì nối chuỗi ngang `[Zalo] Cường`.

## 1. Helper Function: formatNameWithAppSubtitle

### Code đầy đủ với các Import cần thiết

```kotlin
package com.kakao.taxi.liveupdate

import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

/**
 * Format tên với tên ứng dụng hiển thị dưới dạng subtitle.
 * 
 * Ví dụ kết quả:
 * ```
 * Cường
 * Zalo
 * ```
 * 
 * @param originalName Tên gốc (tên người gửi hoặc tiêu đề nhóm)
 * @param appName Tên ứng dụng hiển thị dưới dạng subtitle
 * @param subtitleColor Màu cho subtitle (mặc định: xám nhạt)
 * @param subtitleSizeRatio Tỷ lệ kích thước subtitle so với text gốc (mặc định: 0.75 = 75%)
 * @param useItalic Có in nghiêng subtitle không (mặc định: true)
 * @return CharSequence với định dạng spannable
 */
fun formatNameWithAppSubtitle(
    originalName: CharSequence?,
    appName: String,
    subtitleColor: Int? = 0xFF888888.toInt(), // Màu xám nhạt
    subtitleSizeRatio: Float = 0.75f,
    useItalic: Boolean = true
): CharSequence {
    // Nếu tên gốc null hoặc rỗng, chỉ trả về app name
    if (originalName.isNullOrBlank()) {
        return appName
    }

    // Xây dựng text hoàn chỉnh với SpannableStringBuilder
    val builder = SpannableStringBuilder()
    
    // Thêm tên gốc (không thay đổi style)
    builder.append(originalName)
    
    // Thêm ký tự xuống dòng
    builder.append("\n")
    
    // Tạo SpannableString cho app name với định dạng
    val appNameSpannable = SpannableString(appName)
    
    // Áp dụng RelativeSizeSpan để thu nhỏ chữ (75% kích thước gốc)
    appNameSpannable.setSpan(
        RelativeSizeSpan(subtitleSizeRatio),
        0,
        appName.length,
        SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
    )
    
    // Áp dụng màu nếu được cung cấp
    if (subtitleColor != null) {
        appNameSpannable.setSpan(
            ForegroundColorSpan(subtitleColor),
            0,
            appName.length,
            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
    
    // Áp dụng style in nghiêng nếu được yêu cầu
    if (useItalic) {
        appNameSpannable.setSpan(
            StyleSpan(Typeface.ITALIC),
            0,
            appName.length,
            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
    
    // Append chuỗi đã style vào builder
    builder.append(appNameSpannable)
    
    return builder
}
```

### Phiên bản đơn giản (không in nghiêng)

```kotlin
fun formatNameWithAppSubtitleSimple(
    originalName: CharSequence?,
    appName: String
): CharSequence {
    return formatNameWithAppSubtitle(
        originalName = originalName,
        appName = appName,
        subtitleColor = 0xFF999999.toInt(),
        subtitleSizeRatio = 0.75f,
        useItalic = false
    )
}
```

## 2. Áp dụng vào MessagingStyle

### Code áp dụng vào quá trình rebuild MessagingStyle

```kotlin
private fun applyWearOsSourcePresentation(
    context: Context,
    builder: NotificationCompat.Builder,
    source: Notification,
    sourcePackageName: String
) {
    // Resolve app header để lấy app name
    val appHeader = resolveWearOsAppHeader(context, sourcePackageName) ?: return
    val appName = appHeader.appName
    
    // --- MessagingStyle extraction & rebuild for Wear OS Chat UI ---
    try {
        val sourceStyle = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(source)
        
        if (sourceStyle != null) {
            val originalUser = sourceStyle.user
            val originalConversationTitle = sourceStyle.conversationTitle
            val originalMessages = sourceStyle.messages.takeLast(MAX_WEAR_MESSAGING_STYLE_MESSAGES)
            val isGroupConversation = sourceStyle.isGroupConversation
            
            // ===== BƯỚC 1: Format User name (chủ thiết bị) =====
            val formattedUserName = formatNameWithAppSubtitle(
                originalName = originalUser?.name,
                appName = appName
            )
            
            val newUser = Person.Builder()
                .setName(formattedUserName)  // ✨ Áp dụng tên đã format
                .setKey(originalUser?.key)
                .setIcon(originalUser?.icon)
                .setBot(originalUser?.isBot == true)
                .setImportant(originalUser?.isImportant == true)
                .build()
            
            // Tạo MessagingStyle mới với user đã format
            val newStyle = NotificationCompat.MessagingStyle(newUser)
            newStyle.isGroupConversation = isGroupConversation
            
            // ===== BƯỚC 2: Format Conversation Title (cho group chat) =====
            if (isGroupConversation && !originalConversationTitle.isNullOrBlank()) {
                val formattedTitle = formatNameWithAppSubtitle(
                    originalName = originalConversationTitle,
                    appName = appName
                )
                newStyle.conversationTitle = formattedTitle  // ✨ Áp dụng title đã format
            }
            
            // ===== BƯỚC 3: Rebuild messages với sender name đã format =====
            for (message in originalMessages) {
                val originalSender = message.person
                val messageText = message.text
                val messageTimestamp = message.timestamp
                
                // Format sender name
                val formattedSenderName = if (originalSender != null) {
                    formatNameWithAppSubtitle(
                        originalName = originalSender.name,
                        appName = appName
                    )
                } else {
                    null
                }
                
                // Build Person mới với tên đã format
                val newSender = if (originalSender != null) {
                    Person.Builder()
                        .setName(formattedSenderName)  // ✨ Áp dụng tên đã format
                        .setKey(originalSender.key)
                        .setIcon(originalSender.icon)
                        .setBot(originalSender.isBot)
                        .setImportant(originalSender.isImportant)
                        .build()
                } else {
                    null
                }
                
                // Add message với sender đã format
                newStyle.addMessage(
                    NotificationCompat.MessagingStyle.Message(
                        messageText,
                        messageTimestamp,
                        newSender
                    )
                )
            }
            
            // Áp dụng MessagingStyle đã rebuild vào builder
            builder.setStyle(newStyle)
            builder.setLargeIcon(appHeader.icon.getBitmap())
            
            return
        }
    } catch (e: Exception) {
        Log.w(TAG, "applyWearOsSourcePresentation: failed to rebuild MessagingStyle", e)
    }
    
    // ===== BƯỚC 4: Fallback Builder (cho notifications không phải MessagingStyle) =====
    val originalTitle = source.extras.getCharSequence(Notification.EXTRA_TITLE)
    if (!originalTitle.isNullOrBlank()) {
        val formattedTitle = formatNameWithAppSubtitle(
            originalName = originalTitle,
            appName = appName
        )
        builder.setContentTitle(formattedTitle)  // ✨ Áp dụng title đã format
    }
    
    builder.setLargeIcon(appHeader.icon.getBitmap())
}
```

## 3. Các Import cần thiết

Đảm bảo file `LiveUpdateNotifier.kt` có các import sau:

```kotlin
import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
```

## 4. Kết quả mong đợi

### Trước khi áp dụng (Text-based watermark):
```
Tên hiển thị: [Zalo] Cường
Vấn đề: Quá dài, mất thẩm mỹ
```

### Sau khi áp dụng (SpannableString subtitle):
```
Cường
Zalo
     ^
     |__ Chữ nhỏ hơn (75%), màu xám, in nghiêng
```

## 5. Tùy chỉnh (Optional)

### Thay đổi kích thước subtitle
```kotlin
val formattedName = formatNameWithAppSubtitle(
    originalName = name,
    appName = appName,
    subtitleSizeRatio = 0.70f  // 70% thay vì 75%
)
```

### Thay đổi màu subtitle
```kotlin
val formattedName = formatNameWithAppSubtitle(
    originalName = name,
    appName = appName,
    subtitleColor = 0xFF666666.toInt()  // Xám đậm hơn
)
```

### Không in nghiêng
```kotlin
val formattedName = formatNameWithAppSubtitle(
    originalName = name,
    appName = appName,
    useItalic = false
)
```

## 6. Testing

### Test cases cần kiểm tra:
1. ✅ Tin nhắn 1-1 (Direct message)
2. ✅ Tin nhắn nhóm (Group chat)
3. ✅ Nhiều sender khác nhau trong cùng conversation
4. ✅ Notification không phải MessagingStyle (fallback)
5. ✅ Hiển thị đúng trên Wear OS (Galaxy Watch, v.v.)

### Cách test:
1. Build và cài đặt app
2. Gửi tin nhắn từ các ứng dụng khác nhau (Zalo, WhatsApp, Telegram, v.v.)
3. Kiểm tra hiển thị trên Wear OS device
4. Xác nhận tên ứng dụng xuất hiện dưới dạng subtitle (chữ nhỏ, dòng mới)

## 7. Lưu ý quan trọng

1. **Không nối chuỗi**: Bỏ hoàn toàn cách nối chuỗi kiểu `"[$appName] $name"`
2. **SpannableString**: Sử dụng SpannableStringBuilder để xây dựng text có định dạng
3. **Áp dụng toàn diện**: Áp dụng cho tất cả: User, Senders, ConversationTitle, và Fallback
4. **Maintain compatibility**: Giữ nguyên logic khác của `applyWearOsSourcePresentation`

## 8. Checklist triển khai

- [ ] Thêm helper function `formatNameWithAppSubtitle` vào file
- [ ] Thêm các import cần thiết
- [ ] Refactor User name formatting
- [ ] Refactor ConversationTitle formatting
- [ ] Refactor Sender names trong messages loop
- [ ] Refactor Fallback builder content title
- [ ] Test với tin nhắn 1-1
- [ ] Test với tin nhắn nhóm
- [ ] Test hiển thị trên Wear OS device
- [ ] Verify không còn text-based watermark `[App] Name`

## Tài liệu tham khảo

- `WearOsSubtitleFormatter.kt`: Helper function implementation
- `WearOsIntegrationExample.kt`: Complete integration example
- Android SpannableString documentation: https://developer.android.com/reference/android/text/SpannableString
