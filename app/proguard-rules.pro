# Room (pulled in by WorkManager, which the ads SDK depends on) creates its generated
# database classes by reflection through the no-arg constructor. R8's full mode removes
# that constructor unless it's kept explicitly, which crashes the app at startup.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
