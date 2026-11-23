# بناء الـ APK من غير Android Studio (عن طريق GitHub)

1. اعمل حساب مجاني على https://github.com (لو معندكش).
2. اضغط **New repository** وسمّيه `MR-Motion-Cam` واختاره **Private** لو حابب، وأنشئه.
3. اضغط **uploading an existing file** وارفع **محتويات** مجلد المشروع (مش المجلد نفسه)
   بحيث يظهر `build.gradle` و `app` و `.github` في أول الصفحة.
   - لو مجلد `.github` ماظهرش بعد الرفع: اضغط **Add file > Create new file**،
     واكتب في خانة الاسم: `.github/workflows/build-apk.yml`
     وانسخ جواه محتوى الملف الموجود في المشروع، واضغط **Commit changes**.
4. افتح تبويب **Actions** — هتلاقي عملية "Build APK" شغالة (بتاخد 3-6 دقايق).
   لو مبدأتش لوحدها: اختار "Build APK" ثم **Run workflow**.
5. لما تخلص بعلامة ✅ ادخل عليها، ونزّل من تحت (**Artifacts**) الملف `MR-Motion-Cam-APK`.
   هو ملف zip، فك ضغطه تلاقي `app-debug.apk`.
6. انقل الـ APK للموبايل وثبّته (فعّل "التثبيت من مصادر غير معروفة" لو طلب).
