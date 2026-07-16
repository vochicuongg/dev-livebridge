package com.kakao.taxi.liveupdate

/**
 * FIX #2 + #3 helper reference for LiveUpdateNotifier.kt
 *
 * Hướng dẫn merge (đè hàm cùng tên trong object LiveUpdateNotifier):
 * 1) Thêm KNOWN_CHAT_APP_TITLE_PREFIXES + sanitizePersonDisplayName
 * 2) Thay buildMergedWearMessagingStyle / historySnapshotToMessagingCandidate /
 *    buildDeterministicMessagingStyle / toCompatAction
 * 3) Call-site buildMergedWearMessagingStyle: thêm appName = appName
 * 4) Cuối buildMirroredNotification: extras + setTicker(cleaned)
 * 5) TRUE FALLBACK BigTextStyle: gọi addReplyActionIfNotAlreadyCopied
 *
 * File này KHÔNG tự chạy — chỉ là nguồn copy/merge an toàn khi full Notifier
 * không fit một response. Nếu bạn add lại LiveUpdateNotifier.kt vào chat theo
 * chunk, tôi sẽ trả full file listing đã merge.
 */
object LiveUpdateNotifierMessagingFixes {
    // Placeholder — logic nằm trong comment hướng dẫn phía trên vì
    // các hàm private của LiveUpdateNotifier không thể tách object riêng
    // mà không đổi visibility.
}
