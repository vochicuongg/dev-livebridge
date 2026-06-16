## Bối cảnh & Mục tiêu
Hiện tại, ứng dụng LiveBridge đang thiếu tùy chọn ngôn ngữ Tiếng Việt trong màn hình `Settings -> App config` (Cấu hình ứng dụng). Bạn cần thực hiện việc bổ sung ngôn ngữ Tiếng Việt (`vi`) một cách toàn diện trên cả hai tầng: Tầng giao diện người dùng (Flutter) và Tầng xử lý lõi thông báo/từ điển động (Android Kotlin Assets).

---

## Các tệp tin cần chỉnh sửa và khởi tạo

### 1. Tầng Giao diện & Định tuyến Ngôn ngữ (Flutter Side)

#### 📝 `lib/l10n/app_strings.dart`
- **Nhiệm vụ:** Tìm class hoặc Map định nghĩa chuỗi ngôn ngữ (ví dụ: `en`, `ko`, `zh`...) và bổ sung bản dịch Tiếng Việt (`vi`).
- **Nội dung cần dịch bao gồm:** - Các mục trong Dashboard, Tiêu đề thanh trạng thái (Now Bar, Dynamic Capsule).
  - Các cấu hình chặn thông báo trùng lặp (Deduplication), Quy tắc ứng dụng (Per-App Behavior), Sao chép OTP (OTP Auto-Copy), và Giám sát mạng (Network Speed Monitor).
  - Thuật ngữ chuyên dụng: Dịch sát nghĩa theo giao diện Samsung One UI 7 (ví dụ: "Now Bar", "Viên thuốc thông báo", "Phản hồi xúc giác").

#### 📝 `lib/l10n/app_locale_controller.dart`
- **Nhiệm vụ:** Đăng ký mã ngôn ngữ `vi` vào danh sách `Supported Locales` hệ thống để Flutter nhận diện được mã vùng của Việt Nam.

#### 📝 `lib/widgets/redesign/lb_app_language_sheet.dart`
- **Nhiệm vụ:** Thêm phần tử chọn ngôn ngữ mới vào giao diện đóng mở (Bottom Sheet):
  - Mã: `vi`
  - Tên hiển thị: `"Tiếng Việt"`
  - Icon/Flag (nếu có): Đảm bảo hiển thị đồng bộ với One UI tokens thiết kế của dự án.

---

### 2. Tầng Từ điển Xử lý Thông báo Động (Android Core Side)

#### 🆕 Khởi tạo tệp: `android/app/src/main/assets/liveupdate_dictionary_vi.json`
- **Nhiệm vụ:** Tạo mới tệp JSON từ điển tiếng Việt bằng cách dịch từ tệp `liveupdate_dictionary_en.json`.
- **Yêu cầu kỹ thuật:** Các từ khóa trạng thái (như: *Delivering, Arriving, Driver, Calling, Raining...*) từ các ứng dụng Grab, Shopee Food, Trình phát nhạc phải được dịch sang Tiếng Việt ngắn gọn nhất để tối ưu hóa không gian hiển thị hẹp của thanh **One UI 7 Now Bar**.

#### 📝 Kiểm tra `android/app/src/main/kotlin/com/appsfolder/livebridge/liveupdate/LiveParserDictionary.kt`
- **Nhiệm vụ:** Đảm bảo hàm tải từ điển (`loadDictionary`) có cơ chế tự động nhận diện mã `vi` từ hệ điều hành Android và liên kết chính xác tới tệp Asset vừa tạo ở trên.

---

## Tiêu chuẩn mã nguồn (Code Style & Guidelines)
- **Flutter:** Sử dụng Flutter linting có sẵn trong `analysis_options.yaml`. Không viết đè (hardcode) chuỗi trực tiếp lên widget, bắt buộc phải thông qua cơ chế Localization của dự án.
- **Kotlin:** Giữ nguyên cấu trúc gán luồng không đồng bộ khi đọc file JSON từ thư mục Assets để tránh gây nghẽn (ANR) cho dịch vụ chạy nền `LiveUpdateNotificationListenerService`.
- **Thuật ngữ:** Nhất quán việc sử dụng ngôn ngữ thiết kế One UI của Samsung.

---

## Hướng dẫn thực hiện
Hãy rà soát toàn bộ các tệp tin được chỉ định ở trên, phân tích mã nguồn hiện tại của các ngôn ngữ `en` hoặc `ko` để làm mẫu, sau đó tự động tạo và chỉnh sửa mã nguồn để tích hợp hoàn chỉnh Tiếng Việt vào hệ thống. Sau khi viết xong code, hãy tóm tắt danh sách các chuỗi chính đã được dịch.