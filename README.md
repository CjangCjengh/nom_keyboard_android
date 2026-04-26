<p align="center">
  <img src="icon.png" alt="Bàn phím Hán Nôm" width="200" />
</p>

# Bàn phím Hán Nôm cho Android

> 👉 _Tài liệu này bằng **tiếng Việt**. **[English version here](README.en.md)**._

Bàn phím Hán Nôm hiện đại dành cho Android. Giao diện được thiết kế lại theo phong cách **Gboard** để dễ nhìn và hiện đại hơn.

## 🌟 Tính năng chính

- **Bàn phím QWERTY phong cách Gboard**: phím bo tròn, phím chức năng màu xám, thanh chỉ báo trên phím cách, hai trang ký hiệu `?123` và `=\<`.
- **Gõ tiếng Việt bằng Telex**:
  - Dấu mũ / móc: `aa → â`, `aw → ă`, `ee → ê`, `oo → ô`, `ow → ơ`, `uw → ư`, `dd → đ`
  - Dấu thanh: `s` (sắc), `f` (huyền), `r` (hỏi), `x` (ngã), `j` (nặng), `z` (bỏ dấu)
  - Có thể chọn kiểu đặt dấu **cũ** (hóa, thúy) hay **mới** (hoá, thuý) trong Cài đặt.
- **Gợi ý chữ Hán Nôm ngay khi gõ**: từ điển đơn âm (**6.686** vần, **25.059** ứng viên) và từ điển ghép âm tiết (**1.636** từ, tổng cộng **2.648** mục sau khi phẳng hóa). Chạm vào ô gợi ý để đưa chữ Hán Nôm vào ô nhập.
- **Khoan dung dấu thanh**: gõ không dấu hoặc bỏ dấu đều có thể khớp. Ví dụ: `chunom`, `chữ nôm`, `chữnôm` đều cho ra **𡦂喃 / 𡨸喃**; `vietnam` → **越南**; `hànội` → **河內**.
- **Chuyển đổi theo đoạn**: gõ liền nhiều âm tiết, bàn phím sẽ tách thành từng đoạn để chọn lần lượt, toàn bộ chỉ được xác nhận sau khi chọn xong. Có thể đổi chế độ trong **Cài đặt ▸ Cách xử lý phím cách**.
- **Học tần suất n-gram theo ngữ cảnh**: mỗi chuỗi chữ Hán Nôm đã xác nhận được ghi lại ở mức 1-gram đến N-gram (N tuỳ chỉnh 1–6) để xếp hạng gợi ý dựa theo vài chữ vừa gõ. Một tổ hợp mới cũng tự động trở thành ứng viên cho lần sau.
- **Xuất / nhập tần suất n-gram**: sao lưu toàn bộ dữ liệu đã học ra tệp TSV qua trình chọn tệp hệ thống; khi nhập lại có thể chọn **gộp** (cộng dồn số lần) hoặc **thay thế** toàn bộ. Rất tiện khi đổi thiết bị.
- **Từ điển người dùng**: thêm, sửa, xoá mục riêng trong **Cài đặt ▸ Từ điển người dùng**. Mục của người dùng luôn được ưu tiên hơn từ điển tích hợp, nên bạn có thể ghi đè hoặc mở rộng dữ liệu mặc định. Hỗ trợ **nhập / xuất** theo đúng định dạng tab-separated `khoá<TAB>ứng_viên1 ứng_viên2 …` như từ điển đi kèm — chọn tệp bằng trình chọn tệp hệ thống, khi nhập có thể chọn **hợp nhất** hoặc **thay thế**.
- **Thanh gợi ý có thể mở rộng**: khi ứng viên quá nhiều, nhấn mũi tên hướng xuống ở bên phải để trải rộng bảng ứng viên trên toàn bộ khu vực bàn phím, nhấn lần nữa để thu lại.
- **Cỡ chữ có thể điều chỉnh** cho cả phím chính và thanh gợi ý.
- **Giao diện sáng / tối / theo hệ thống**.
- **Phản hồi rung** và **âm thanh phím** có thể bật tắt.
- **Phông chữ Han-Nom Gothic nhúng sẵn** (~12 MB) để hiển thị các ký tự Hán Nôm trong bảng gợi ý.

## 🚀 Cách biên dịch (Android Studio)

1. Mở Android Studio (Giraffe 2022.3 hoặc mới hơn).
2. Chọn **Open** và trỏ tới thư mục `nom_keyboard/`.
3. Android Studio sẽ phát hiện thiếu `gradle-wrapper.jar` và tự động tải wrapper khi bạn bấm **Sync / Use Gradle wrapper**.
4. Đợi Gradle tải các phụ thuộc (Android Gradle Plugin 8.5.2, Kotlin 1.9.25, Gradle 8.9).
5. Chọn **Build ▸ Build Bundle(s) / APK(s) ▸ Build APK(s)** để sinh tệp `.apk`.
6. Cài APK lên điện thoại, sau đó:
   - Vào **Cài đặt ▸ Ngôn ngữ & bàn phím ▸ Quản lý bàn phím** để bật **Bàn phím Hán Nôm**.
   - Mở ứng dụng có ô nhập văn bản, chọn **Bàn phím Hán Nôm** từ trình chọn bàn phím.

## 📜 Yêu cầu hệ thống

- Android 7.0 (API 24) trở lên.
- Khoảng 20 MB không gian cài đặt (do phông chữ nhúng).
