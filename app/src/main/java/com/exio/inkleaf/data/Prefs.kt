package com.exio.inkleaf.data

/** DataStore 里存的枚举名 → 枚举值。存储值损坏或来自删改过枚举的 旧版本时回退默认值（各 Settings Repository 共用的解析规则）。 */
internal inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
    this?.let { stored -> runCatching { enumValueOf<T>(stored) }.getOrNull() } ?: default

/** 同 [toEnum]，但未设置或存储值损坏时返回 null（用于"未设置"与某个枚举值语义不同的偏好）。 */
internal inline fun <reified T : Enum<T>> String?.toEnumOrNull(): T? =
    this?.let { stored -> runCatching { enumValueOf<T>(stored) }.getOrNull() }
