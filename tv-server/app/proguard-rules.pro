# 反射调用 ActivityThread#getInstrumentation 需要保留相关符号
-keep class android.app.ActivityThread { *; }
-keep class android.app.Instrumentation { *; }
-dontwarn org.json.**
