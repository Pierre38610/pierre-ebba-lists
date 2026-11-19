# Keep AndroidBridge methods
-keepclassmembers class com.pierreebba.sharedlists.MainActivity$AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}
