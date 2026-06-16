## Bối cảnh
Tôi đang phát triển ứng dụng LiveBridge (phiên bản Android/Samsung). Trong tệp `lib/l10n/app_strings.dart`, ngôn ngữ Tiếng Việt (`vi`) đã được thêm vào, nhưng hiện tại có rất nhiều tham số `vi:` đang bị "hardcode" bằng tiếng Anh thay vì được dịch sang tiếng Việt (ví dụ: `vi: 'Notification permission granted.'`).

## Nhiệm vụ của bạn
Hãy rà soát từ trên xuống dưới tệp `app_strings.dart` tôi cung cấp. Tìm TẤT CẢ các dòng `vi:` đang chứa nội dung tiếng Anh và dịch chúng sang tiếng Việt một cách tự nhiên, chuẩn xác nhất. 

## Nguyên tắc dịch thuật & Kỹ thuật
1. **Ngữ cảnh hệ thống Android:** Dùng văn phong của hệ điều hành, ngắn gọn, thân thiện (ví dụ: "Granted" -> "Đã cấp quyền", "Denied" -> "Bị từ chối").
2. **Bảo toàn biến số:** Tuyệt đối giữ nguyên các biến string interpolation của Dart như `$current`, `$latest`, `$appLabel`, `$time`.
3. **Từ khóa riêng:** 
   - `Live Updates`, `LiveBridge`, `Now Bar`, `GitHub`, `JSON`, `Payload`, `OTP` -> **Giữ nguyên**.
   - `Island` / `Capsule` -> Có thể dịch là "Viên thuốc" hoặc "Capsule" tùy ngữ cảnh.
   - `Dedup` (Deduplication) -> Lọc trùng / Chống trùng lặp.
   - `Bypass` -> Bỏ qua / Vượt rào.
4. **Tránh lỗi Syntax Dart:** Chú ý cẩn thận các dấu nháy đơn (`'`) và ký tự escape (`\'`). Nếu câu tiếng Việt có dấu nháy đơn, hãy bọc toàn bộ chuỗi bằng dấu nháy kép (`" "`) để không gây lỗi biên dịch.

Vui lòng trả về cho tôi mã nguồn `app_strings.dart` sau khi đã dịch hoàn chỉnh tất cả các chuỗi `vi:`.