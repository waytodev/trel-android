# Keep line numbers and source files so `trel symbols upload --kind proguard` can retrace stacks.
-keepattributes SourceFile,LineNumberTable
# Keep the public API and the crash handler entry points intact.
-keep class to.trel.Trel { *; }
-keep class to.trel.TrelOptions { *; }
-keep class to.trel.Breadcrumb { *; }
-keep class to.trel.TrelLevel { *; }
