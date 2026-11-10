# Proguard & R8 rules for Android USB Webcam

# Keep all native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep UvcNative and UvcEventCallback called from C++ JNI
-keep class com.andrewchqng.usbwebcam.webcam.UvcNative { *; }
-keep interface com.andrewchqng.usbwebcam.webcam.UvcEventCallback { *; }
-keep class * implements com.andrewchqng.usbwebcam.webcam.UvcEventCallback {
    public void onStreamStateChanged(boolean, boolean, int, int, int);
    public void onHostConnectionChanged(boolean);
}

# Keep all webcam controllers and foreground services
-keep class com.andrewchqng.usbwebcam.webcam.** { *; }

# Keep libsu RootService and IPC classes
-keep class com.topjohnwu.superuser.** { *; }
-keep class com.andrewchqng.usbwebcam.hid_utils.UsbGadgetService { *; }
-keep class com.andrewchqng.usbwebcam.hid_utils.** { *; }

# Keep all settings, preferences, and data models
-keep class com.andrewchqng.usbwebcam.settings.** { *; }
-keep class com.andrewchqng.usbwebcam.webserver.** { *; }

# Keep Voyager Screen classes
-keep class * implements cafe.adriel.voyager.core.screen.Screen { *; }
-keep class cafe.adriel.voyager.** { *; }
