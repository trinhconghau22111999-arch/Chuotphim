plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.btremote"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.btremote"
        minSdk = 28          // BluetoothHidDevice API cần Android 9 (API 28)
        // targetSdk CỐ Ý để 31 (Android 12), KHÔNG để 33+: từ Android 13 trở đi, Google
        // chặn app tự gọi BluetoothAdapter.enable()/disable() nếu targetSdk >= 33 (2 hàm
        // này luôn trả về false, không làm gì) — hành vi này chỉ dựa vào targetSdk app
        // khai báo, không phụ thuộc Android thật của máy đang chạy. App này cần tự tắt/bật
        // Bluetooth (xem MainActivity.reconnectWithReset()) để tránh lỗi không connect
        // được khi máy từng ghép nối Bluetooth thường với TV trước đó — nên phải giữ
        // targetSdk < 33. App không phát hành qua Play Store (cài tay/APK riêng) nên
        // không bị ràng buộc yêu cầu targetSdk mới nhất của Play Store.
        targetSdk = 31
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
