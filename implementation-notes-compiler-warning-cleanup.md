# Implementation Notes: Compiler Warning Cleanup

## Scope

清理用户报告的 5 条 Kotlin `Unnecessary safe call` warning，不改变运行时行为。

## Findings

- 项目解析 OkHttp 5.4.0；实际 `okhttp-android` AAR 中
  `okhttp3.Response.body` 的类型为非空 `ResponseBody`。
- `DiscoverViewModel` 的第二个 `session` 访问只会在前一个短路条件为 false 时执行；由于
  `firstPageRevision` 为非空 `String`，此时编译器已确认 `session` 非空。

## Changes

- 移除 4 处针对非空 `Response.body` 的安全调用及不可达 fallback。
- 移除 1 处已经过控制流智能转换的 `session` 安全调用。

## Validation

- 使用 exact-version library index 核对 OkHttp 5.4.0 API。
- 运行相关 JVM 单测及 `git diff --check`。
- 未运行独立 Gradle compile、Android/instrumentation test、lint 或 assemble。

## Deviations

- 无。
