# 本项目自写代码全部保留语义命名（不靠混淆隐藏意图）。
# 这里只放"工具链/反射/序列化"必须的规则。

# 枚举 used by name —— 配置项按名字存 DataStore，key 是稳定契约
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Parcelable（跨进程/状态恢复）
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# 行号保留：崩溃栈要能对到源码
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# smbj 带 Kerberos（org.ietf.jgss）与 mbassador 带 EL 表达式过滤器（javax.el）两条可选路径，
# Android 上没有这两套类。我们只用 NTLM 与基本事件分发，所以按「缺类不报错」处理。
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
