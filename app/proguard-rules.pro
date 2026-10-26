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
