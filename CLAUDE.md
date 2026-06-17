## Mục tiêu đầu ra
Loại bỏ hoàn toàn cấu trúc "[$appName]" ra khỏi tiêu đề thông báo khi đồng bộ sang Wear OS, đảm bảo tiêu đề giữ nguyên chuẩn 100% như ứng dụng gốc.

## Giải pháp (Nhiệm vụ của bạn)
Mở tệp `LiveUpdateNotifier.kt` và tìm dòng code gán `contentTitle` bên trong khối `if (shouldRemoveOriginal)`:`builder.setContentTitle("[$appName] $originalTitle")`

## Lưu ý: 
Đảm bảo không ảnh hưởng đến bất kỳ cờ (flags) rung, âm thanh, channel id, hay logic RemoteInput nào mà chúng ta đã làm thành công ở các bước trước. Chỉ duy nhất thay đổi logic gán setContentTitle mà thôi.