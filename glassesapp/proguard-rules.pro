# Rokid CXR-S bridge: classes and callbacks are used from native code (JNI); keep them intact.
-keep class com.rokid.cxr.** { *; }
-keepclassmembers class * implements com.rokid.cxr.CXRServiceBridge$StatusListener { *; }
-keepclassmembers class * implements com.rokid.cxr.CXRServiceBridge$MsgCallback { *; }
-dontwarn com.rokid.**
