# androidx.annotation is a compileOnly, build-only dependency (CLASS-retention annotations such as
# @ChecksSdkIntAtLeast): it is never bundled, so R8 must not fail when it cannot resolve it.
-dontwarn androidx.annotation.**
