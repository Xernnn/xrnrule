# Release builds are shrunk by R8 (since 0.8.x). What follows keeps what R8 can't see being used.

# kotlinx.serialization: the saved world (world.json), the shared state and every message to the
# server and the extension are read through each class's generated serializer. Its own rules keep
# those; these say so for this app's and the engine's classes whatever its version ships.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.xern.xrnrule.** {
    *** Companion;
}
-keepclasseswithmembers class com.xern.xrnrule.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Classes something builds by reflection: R8 can't see that call, so it removes the constructor unless
# a rule names it. R8's full mode (AGP's default) keeps no constructor for a rule that names only the
# class, and the libraries' own rules don't all say which (xrnimg's 0.1.0 release crashed at start
# without WorkManager's WorkDatabase_Impl()). The app's checkReleaseKeeps task fails a build that loses one.
# Room (inside WorkManager): Room.databaseBuilder makes the generated <Name>_Impl with Class.newInstance().
-keep class * extends androidx.room.RoomDatabase { <init>(); }
# WorkManager: its default WorkerFactory makes each worker by (Context, WorkerParameters), the input
# merger a WorkSpec names with no arguments.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.InputMerger { <init>(); }
# androidx.startup: InitializationProvider makes each initializer the manifest names.
-keep class * implements androidx.startup.Initializer { <init>(); }
