# App 的 R8 规则。策略：删码 + 优化 + 资源收缩全开，但不混淆。
#
# 个人项目的取舍：混淆是 R8 四项工作里唯一与性能无关的（只省体积），
# 却是全部运维负担的来源（mapping.txt 按版本保管、堆栈还原）。
# 关掉它之后 release 崩溃堆栈直接可读，无需任何还原工序。
-dontobfuscate

# 行号保留：不混淆时类名/方法名已是真名，行号让堆栈精确到源码行
-keepattributes SourceFile,LineNumberTable

# 其余刻意留空：
# - Room / Coil / DataStore 自带 consumer rules，随依赖自动合并
# - kotlinx-serialization 1.5.1+ 在制品内嵌 R8 规则（META-INF/com.android.tools/r8），
#   @Serializable 路由（ShelfRoute / ReaderRoute / SettingsRoute）的序列化器
#   由编译期内联解析，不走反射，无需额外 keep
# - Native enhancement uses name-based JNI lookup. Keep the bridge and its native methods stable.
-keep class com.exio.inkleaf.data.enhancement.NativeEnhancementBridge { *; }
#
# release 出现 ClassNotFound / NoSuchMethod 类崩溃时，先怀疑新引入的
# 反射用法（摇树删码误删），在这里补对应 -keep，而不是关闭 R8
