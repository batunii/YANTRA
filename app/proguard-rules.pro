# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class ie.napkin.supertasks.**$$serializer { *; }
-keepclassmembers class ie.napkin.supertasks.** {
    *** Companion;
}
-keepclasseswithmembers class ie.napkin.supertasks.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# JGit compiles against a full JRE and Android is not one. Every class below is referenced from a
# code path that cannot be reached here, and R8 refuses to finish while it cannot resolve them —
# which is why there was no release build at all until this was written down.
#
#   ProcessHandle / java.lang.management  a PID lock and a JMX MBean for the window cache, both
#                                         used only by JGit's own diagnostics
#   javax.management                      the MBean registration those diagnostics publish to
#   org.ietf.jgss                         Kerberos/SPNEGO HTTP auth; this app authenticates with a
#                                         GitHub token over HTTPS and never negotiates
#   org.slf4j.impl.StaticLoggerBinder     slf4j's 1.x binding lookup, with no binding on the path
-dontwarn java.lang.ProcessHandle
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.impl.**

# JGit's error messages, and why the app died without them.
#
# Every string JGit can throw at you lives in JGitText, a class whose public String fields are
# filled in at runtime: NLS reflects over the declared fields, matches them by name against
# JGitText.properties, and reaches the class itself through
# `bundleClass.getConstructor().newInstance()`. Nothing in the program ever names that constructor,
# so R8 removed it — and the first `git init` this app performs went down with
# `NoSuchMethodException: <init> []` before it had drawn a screen. This was fatal on first run, on
# every device, in the only variant anyone would ever install.
#
# Keeping the bundles whole is the whole fix: the constructor to build one, the fields to fill.
-keep class org.eclipse.jgit.nls.TranslationBundle { *; }
-keep class * extends org.eclipse.jgit.nls.TranslationBundle { *; }

# JGit finds its filesystem, ssh and http backends through ServiceLoader, which is reflection by
# another name — the implementation is named only in META-INF/services and R8 cannot see the edge.
-keep class * implements org.eclipse.jgit.util.FS$FSFactory { *; }
-keep class * extends org.eclipse.jgit.transport.TransportProtocol { *; }
-keep class org.eclipse.jgit.util.SystemReader { *; }
-keep class * extends org.eclipse.jgit.util.SystemReader { *; }
