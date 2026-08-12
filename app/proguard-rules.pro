# DhinaSuthra release rules. Keep Room-generated code and broadcast receivers.
-keep class com.dhinasuthra.app.sensing.** { *; }
-keep class com.dhinasuthra.app.reminders.ReminderReceiver { *; }
-keep class com.dhinasuthra.app.reminders.ReminderActionReceiver { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { *; }
-dontwarn org.jetbrains.annotations.**
