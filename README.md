<p align="center"><img src="icon.png" alt="Bàn phím Hán Nôm" width="96" /></p>

# Bàn phím Hán Nôm cho Android

[English](README.en.md)

Gõ tiếng Việt bằng Telex rồi chạm vào gợi ý để nhập chữ Hán Nôm. Bàn phím QWERTY có giao diện sáng/tối theo phong cách Gboard và biểu tượng tròn cho Pixel.

## Cài đặt và sử dụng

1. Tải APK từ [GitHub Releases](https://github.com/CjangCjengh/nom_keyboard_android/releases/latest) và cài trên Android 7.0 trở lên. Khi cập nhật, cài đè để giữ từ điển cá nhân.
2. Mở **Bàn phím Hán Nôm**. Dùng hai mục đầu để bật bàn phím trong cài đặt hệ thống và chọn nó làm bộ gõ.
3. Gõ một hoặc nhiều âm tiết. Chạm vào ứng viên để chọn chữ hoặc chạm mũi tên để mở rộng danh sách.

Trong **Tuỳ chọn**, bạn có thể đổi kiểu đặt dấu, cách xử lý phím cách, viết tắt, cỡ chữ và phông Han-Nom Gothic. Từ điển cá nhân và dữ liệu tần suất n-gram đều hỗ trợ nhập/xuất TSV.

## Biên dịch

Mở thư mục gốc trong Android Studio, dùng JDK 17 và Gradle 8.9. Để tạo APK thử nghiệm, chạy `gradle :app:assembleDebug`. Khi ký bản phát hành, sao chép `keystore.properties.template` thành `keystore.properties` và điền thông tin khoá riêng.
