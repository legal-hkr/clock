# Clock's R8 rules. The libraries (Room, kotlinx.serialization, Navigation 3) bring their own.

# Enum values are stored by name (DataStore settings, backups): keep the names as
# written, whatever R8 makes of the classes around them.
-keepclassmembers enum sh.hnet.clock.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Stack traces in error details stay readable enough to report
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Reproducible builds (F-Droid compares its build with the released APK): R8 9.5 narrows the
# Continuation parameter of these two methods to ContinuationImpl in some runs and not in others, a
# race between its threads that shows more often on machines with few cores, and classes.dex changes
# with it. Kept as written, they come out the same every time.
-keep class kotlinx.coroutines.flow.FlowKt__LimitKt {
    *** *emitAbort*(...);
}
