# AndroidManifest and Kuikly generated page registries are referenced by name.
-keep class io.github.yukiykchen.dsh.KRApplication { *; }
-keep class io.github.yukiykchen.dsh.KuiklyRenderActivity { *; }
-keep class io.github.yukiykchen.dsh.relay.DshQrScanActivity { *; }
-keep class io.github.yukiykchen.dsh.relay.DshRelayForegroundService { *; }
-keep class io.github.yukiykchen.dsh.ssh.DshSshForegroundService { *; }
-keep class io.github.yukiykchen.dsh.adapter.** { *; }
-keep class io.github.yukiykchen.dsh.module.** { *; }
-keep class com.tencent.kuikly.** { *; }
-keep class com.tencent.kuiklybase.** { *; }
-keep class org.apache.sshd.** { *; }
-keep class net.shantu.kuiklysqlite.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepclassmembers class ** { @com.tencent.kuikly.core.annotations.Page <methods>; }

# SSHD references desktop-only optional integrations that Android never loads.
-dontwarn java.rmi.RemoteException
-dontwarn java.rmi.ServerException
-dontwarn javax.management.MBeanException
-dontwarn javax.management.ReflectionException
-dontwarn javax.security.auth.callback.NameCallback
-dontwarn javax.security.auth.login.AppConfigurationEntry$LoginModuleControlFlag
-dontwarn javax.security.auth.login.AppConfigurationEntry
-dontwarn javax.security.auth.login.Configuration
-dontwarn javax.security.auth.login.CredentialException
-dontwarn javax.security.auth.login.FailedLoginException
-dontwarn javax.security.auth.login.LoginContext
-dontwarn org.apache.tomcat.jni.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.impl.StaticLoggerBinder
