-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# The apps load it by name from the tachiyomi.extension.class metadata
-keep class keiyoushi.runtime.host.RuntimeFactory { <init>(); }

# Module entry points: extensions call them through RuntimeModule, by reflection
-keep class keiyoushi.runtime.**.Entry { public *; }

# The contract's own consumer rules (contract/proguard-rules.pro) keep the contract interfaces'
# methods and @Throws, so Entry's overrides keep their names.

# The launcher (plain Java, run without the Kotlin stdlib) reads this generated class: keep R8 from
# merging Kotlin helpers into it
-keep class keiyoushi.runtime.host.BuildModules { *; }
