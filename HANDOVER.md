# TranslateEnhanced 交接文档（Handover）

> 适用对象：准备接手维护 / 二次开发本插件的人
> 最后更新：2026-08-03（对应代码 `6331a41`）
> 插件仓库：`https://github.com/kizfive/TranslateEnhanced`
> 安装包：`https://github.com/kizfive/TranslateEnhanced/raw/builds/TranslateEnhanced.zip`

---

## 0. 一句话结论（先看这个）

这是一个 **Aliucord（Discord 修改框架）翻译插件**，支持 Google 翻译和 OpenAI 兼容大模型双后端，按频道自动翻译。

**接手本项目最大的雷，不是业务逻辑，而是一个环境特性：**
Discord 本体经过 R8 混淆后，`SettingsAPI.getString()` 和 `Message.content` 在运行时的真实类型**不是** `java.lang.String`，而是一个混淆后的 `CharSequence` 包装类（形如 `d0.d0.b`）。对它调用任何 Kotlin 标准库的 `String`/`CharSequence` 扩展（`.isBlank()`、`.trim()`、`${}` 模板等）都会触发：

```
java.lang.ClassCastException: d0.d0.b cannot be cast to kotlin.collections.IntIterator
```

**而且 R8 会"聪明地"把你写的类型转换代码（`.toString()`、`String.format`、反射等）当作冗余优化掉**，除非你用特定手法。本项目花了大量提交才彻底解决，详见第 6 节。**改任何涉及这些值的字符串代码前，先读第 6 节。**

---

## 1. 技术栈与构建环境

| 项 | 值 |
|---|---|
| 语言 | Kotlin 1.5.21 |
| 构建 | Gradle 7.5.1（wrapper 在 CI 内重新下载），Aliucord Gradle 插件 `bbcd8a8` |
| 编译 SDK | 31，minSdk 24，targetSdk 31 |
| Java 兼容 | 11（`jvmTarget = "11"`），且带 `-Xno-call-assertions` / `-Xno-param-assertions` / `-Xno-receiver-assertions` |
| Discord 目标版本 | 126021（`minimumDiscordVersion`） |
| 关键依赖 | `com.discord:discord:126021`、`com.aliucord:Aliucord:adf80a8`、`androidx.appcompat:1.3.1`、`com.google.android.material:material:1.4.0` |
| 运行平台 | Android（Aliucord 注入到 Discord APK 内） |

**本地构建命令：**

```bash
./gradlew :TranslateEnhanced:make generateUpdaterJson
```

产物在 `TranslateEnhanced/build/TranslateEnhanced.zip`，拷到 `/sdcard/Aliucord/plugins/` 即可。

**CI（`.github/workflows/build.yml`）：** push 到 `main` 后自动构建，并把 `*.zip` + `updater.json` 推到 `builds` 分支。需要 `permissions: contents: write`（当初因为缺这个权限导致 push 失败，已修）。

---

## 2. 目录结构

```
TranslateEnhanced/
├── .github/workflows/build.yml        # CI：构建并推送到 builds 分支
├── build.gradle.kts                   # 根构建脚本（定义 aliucord/android 扩展、依赖）
├── settings.gradle.kts
├── README.md                          # 简短用户向说明
├── .gitignore
└── TranslateEnhanced/
    └── src/main/
        ├── AndroidManifest.xml
        └── kotlin/com/aliucord/plugins/translate/
            ├── Translate.kt                 # 插件入口：patcher 注入点
            ├── Constants.kt                 # 所有常量（API URL、设置 key、日志路径）
            ├── TranslateResult.kt           # sealed class：Success / Error
            ├── LanguageMap.kt               # 语言名→代码 map，斜杠命令选项
            ├── TextCleaner.kt               # 翻译前清理 HTML/URL/Emoji（全部占位符化，译文后统一还原）
            ├── TranslateController.kt       # 调度中心：选后端、缓存、线程池、降级
            ├── PluginSettings.kt            # 设置页 UI
            ├── auto/
            │   ├── AutoTranslateManager.kt   # 按频道开关 + 失败暂停
            │   └── LanguageDetector.kt       # 启发式语言检测（区分简繁）
            ├── backend/
            │   ├── TranslatorBackend.kt      # 后端接口
            │   ├── GoogleTranslator.kt      # Google 后端（用 Aliucord Http）
            │   ├── LLMTranslator.kt         # 大模型后端（原生 HttpURLConnection）
            │   └── LLMApiHelper.kt          # 测试连接 / 拉模型列表
            ├── strings/
            │   ├── IStrings.kt              # 字符串接口
            │   ├── StringsEn.kt             # 英文字符串
            │   └── StringsZh.kt             # 中文字符串
            └── utils/
                ├── ResourceUtils.kt         # ★ 核心：toRealString / safeIsBlank / safeGetString
                ├── DebugLogger.kt           # 统一日志（单文件 translate.log，DEBUG/WARN/ERROR 分级）
                ├── TranslateUnescaper.kt    # Google 返回的 \uXXXX 反转义
                └── WidgetUtils.kt           # forceRerenderMessage（强制刷新单条消息）
```

---

## 3. 核心架构与数据流

```
用户操作 / 新消息到达
        │
        ▼
  Translate.kt（patcher 注入）
   ├─ patchChatList()         → StoreStream.handleMessageCreate：自动翻译入口
   ├─ patchProcessMessageText → 渲染"翻译中..."/译文/原文
   ├─ patchMessageContextMenu → 注入"翻译/显示原文/自动翻译"按钮
   └─ registerTranslateCommand → /translate 斜杠命令
        │
        ▼
  TranslateController（调度中心）
   ├─ resolveBackend()        → 选 Google / LLM
   ├─ translateSync()        → 同步翻译（在后台线程调用）
   │     ├─ TextCleaner.clean()       预处理
   │     ├─ LanguageDetector.shouldTranslate()  是否需翻译
   │     ├─ backend.translate()       实际翻译
   │     └─ LLM 失败 → 降级 Google
   ├─ translateAsync()       → 线程池执行 + 主线程刷新
   └─ 缓存 / pending / 降级
        │
        ▼
  TranslatorBackend 实现
   ├─ GoogleTranslator  → Aliucord Http（GET）
   └─ LLMTranslator    → 原生 HttpURLConnection（POST /v1/chat/completions）
```

**线程模型：**
- 翻译任务提交到 `TranslateController` 自有的 `ExecutorService`（固定 2 线程，双重检查锁创建）。
- 之前用过 `Utils.threadPool`，在 Aliucord 环境下会**静默崩溃**（不报错但任务不执行），已弃用。
- 结果通过 `Handler(Looper.getMainLooper()).post { }` 回到主线程刷新 UI。
- `pendingMessages`（ConcurrentHashMap 的 key set）防止同一消息重复翻译；`translatedMessages` 是带容量上限（300）的 LRU 缓存。

---

## 4. 关键模块说明

### 4.1 `Translate.kt`（插件入口）
- `@AliucordPlugin` 注解，`settingsTab` 指向 `PluginSettings`。
- `load()`：初始化 `TranslateController`、`AutoTranslateManager`、debug 模式。
- `start()`：打 4 个 patch。
- **自动翻译入口**：hook `StoreStream.handleMessageCreate(com.discord.api.message.Message)`（`g()`=channelId、`o()`=messageId）。⚠️ 旧代码 hook 的 `WidgetChatList.onNewMessage` 在 Discord 126021 中**已不存在**，patch 会静默失败，自动翻译完全失效——这是已修复的历史坑。
- **编辑检测**：`patchProcessMessageText` 里比较 `data.sourceText` 与 `message.content.toRealString()`，不一致（消息被编辑）就 `invalidate(id)` 显示原文。
- 消息菜单新增"翻译 / 显示原文 / 重新翻译"按钮，以及自动翻译开关/恢复按钮。
  - "重新翻译"仅在已有译文缓存时显示：绕过旧译文强制重新调用后端（`force = true`），
    大模型后端会收到"不要原样回显"的额外提示，用于应对"译文和原文一样"的情况。

### 4.2 `TranslateController.kt`（调度中心）
- `translateSync()`：所有防御转换都先 `toRealString()`；空译文/异常视为失败；LLM 失败自动回退 Google 并在主线程 Toast 提示。
- **内容还原**：`TextCleaner.clean()` 把 emoji/Discord 标记（提及、自定义表情、时间戳、HTML）替换为 `[[EMOJI_n]]` / `[[TAG_n]]` 占位符，翻译成功后由 `TextCleaner.restoreAll()` 统一还原；若翻译引擎吞掉占位符，缺失内容会追加到译文末尾，保证不丢。
- **URL 处理（默认不清洗）**：`cleanUrl` 默认关闭，链接随原文直接交给翻译引擎（LLM 提示词要求原样保留，Google 原生保留），译文由 `DiscordParser` 重新渲染时自动链接化。翻译前 `collectUrls()` 记录原始完整链接，翻译后 `ensureUrlsPresent()` 校验，被改写/丢失的链接自动补回译文末尾。
- **URL 占位保护模式**：`cleanUrl` 开启时回到占位符方案（`[[URL_n]]`，链接不参与翻译、译文原位还原）。`URL_REGEX` 会在 CJK/全角/emoji 等字符处截断并裁剪尾部标点（如 `https://a.com/foo)。` → `https://a.com/foo`），避免中文无空格时把后续文本吞进链接。
- `translateSync()` 支持 `force` 参数：`resolveBackend(force)` 会把 `forceRetranslate` 传给 LLM 后端；译文与原文相同时额外写一条 WARNING 日志，便于定位"原样返回"是模型回显还是文本本就在目标语言。
- `translateAsync()`：异常时记录崩溃日志 + 在 Toast 开头带上崩溃位置（`@Class.method:line`），并清除占位符恢复原文。
- `resolveBackend()`：直接把 `settings.getString(...)` 的原始返回值 `as Any` 传给 `LLMTranslator`（关键手法，见第 6 节），外层 try-catch 失败回退 Google。

### 4.3 `backend/LLMTranslator.kt`（大模型后端）
- **构造函数参数类型是 `Any` 不是 `String`**（见第 6 节），内部用 `toRealString()` 转真实字符串。
- 新增 `forceRetranslate: Boolean = false` 构造参数：为 true 时 user prompt 追加"不要原样回显，若源文本已在目标语言则原样返回"的提示，用于"重新翻译"菜单项。
- user prompt 明确要求：不翻译 URL（http/https 原样保留）、原样保留 `[[EMOJI_n]]` / `[[TAG_n]]` 等占位符。
- 用 **原生 `HttpURLConnection`**，不用 Aliucord `Http`：
  - 原因：Aliucord `Http` 在 4xx/5xx 时读 `getInputStream()` 失败，抛无意义的 `"closed"`，掩盖真实错误。原生 `HttpURLConnection` 能读 `errorStream` 拿到真实错误。
- `max_tokens` 1024（某些服务商限制 2048）。
- `buildUrl()` 自动处理 Base URL 是否带 `/v1`（带不带都 OK，不会拼出 `/v1/v1`）。
- 失败自动重试一次（间隔 1s）。
- System prompt：`You are a professional translator. Only output the translated text, no explanations.`
- User prompt：`Translate [from X ]to Y. Only return the translated text, nothing else.\n\n<文本>`
- 返回仅 `.trim()`，无 markdown 处理。

### 4.4 `backend/GoogleTranslator.kt`
- 用 Aliucord `Http`（GET），超时 20s。
- 响应用 `TranslateUnescaper.unescape()` 处理 `\uXXXX` 转义。
- 429 特殊处理为"限流"提示。

### 4.5 `backend/LLMApiHelper.kt`
- `testConnection()` / `fetchModels()`：参数类型 `Any`，原生 `HttpURLConnection`，返回 sealed result（Success/Error）。
- `fetchModels` 调 `/v1/models`，解析 `data[].id`。

### 4.6 `auto/AutoTranslateManager.kt`
- 按频道持久化开关：`autoTranslate_enabled_{channelId}`。
- 连续失败 3 次自动暂停（`pausedChannels`），菜单可手动恢复。

### 4.7 `auto/LanguageDetector.kt`
- 基于 Unicode 范围的启发式检测，覆盖 CJK/Latin/Cyrillic/Arabic/Thai/Japanese/Korean 等。
- **区分简繁**：繁体常用字命中率 ≥ 1/3 时判 `zh-TW`，否则 `zh-CN`。
- `shouldTranslate()`：检测语言 == 目标语言则跳过；中文按完整代码比较（简繁互翻）。

### 4.8 `utils/DebugLogger.kt`
- **统一日志系统**：所有日志写入同一个文件 `/sdcard/Aliucord/translate.log`（常量 `LOG_PATH`）。
- DEBUG 级别（`log()` / `logTranslation()`）仅在 Debug Mode 开启时写入；**WARN/ERROR 级别无条件写入**，`logCrash()` 即 ERROR 级别，崩溃日志不会因开关丢失。
- `clearLog()` 清除单一日志文件。

### 4.9 字符串（`strings/`）
- `IStrings` 接口 + `StringsEn`/`StringsZh` 两份实现，`Context.getStrings()` 按系统语言选。
- **改 UI 文案必须同时在三处加同一字段**，否则编译不过（`IStrings` 是接口，缺实现会编译失败）。

---

## 5. 设置项（`Constants.kt` 中的 `SETTINGS_KEY_*`）

| Key | 含义 | 默认 |
|---|---|---|
| `defaultLanguage` | 默认目标语言 | `zh-CN` |
| `cleanHtml` | 翻译前去 HTML 标签 | `true` |
| `cleanUrl` | 链接保护：开启=占位保护（链接不参与翻译、原位还原）；关闭=链接交给翻译引擎并校验补回 | `false` |
| `cleanEmoji` | 翻译前去 Emoji | `true` |
| `backend` | `google` / `llm` | `google` |
| `llmBaseUrl` | 大模型 Base URL | `""` |
| `llmApiKey` | API Key | `""` |
| `llmModel` | 模型名 | `gpt-4o-mini` |
| `llmSystemPrompt` | 系统提示词 | `DEFAULT_SYSTEM_PROMPT` |
| `debugMode` | 调试开关 | `false` |

> 自动翻译频道开关是动态 key：`autoTranslate_enabled_{channelId}`。

---

## 6. ⚠️ 必读：R8 混淆与 `d0.d0.b` 崩溃（本项目的核心坑）

### 6.1 现象
```
java.lang.ClassCastException: d0.d0.b cannot be cast to kotlin.collections.IntIterator
```
崩溃栈通常指向某个 `isBlank()` / `trim()` / `substring()` / `${}` 等字符串操作。

### 6.2 根因
1. Discord 本体被 R8 重度混淆。**`SettingsAPI.getString()` 声明返回 `String`，但运行时实际返回混淆后的 `CharSequence` 包装类 `d0.d0.b`**。
2. 同样，`Message.content` 在运行时也可能是这种混淆类型。
3. Kotlin 的 `String.isBlank()` 等扩展，本质是调用 `CharSequence` 上的内联扩展。R8 按编译期类型（`String`）做了内联优化，把迭代逻辑写成了 `IntIterator` 强转；运行时不兼容混淆类型，于是炸在 `IntIterator`。

### 6.3 为什么"常规写法"都救不了
项目早期逐个尝试过，全部失败（被 R8 优化掉）：
| 尝试 | 结果 |
|---|---|
| `.toString()` | 被优化掉（R8 认为"本就是 String"）|
| `"$x"` 字符串模板 | 被优化掉 |
| `StringBuilder().append(x)` | 被优化掉 |
| 反射调 `toString()` | **部分可用**（见下）|
| 反射 + `PrintStream` | 可行但啰嗦 |

### 6.4 ✅ 最终稳定方案（`utils/ResourceUtils.kt`）

**方案 A — `toRealString()`（首选，用于外部传入的值）**
通过 `CharSequence` 接口的反射方法 `length()` / `charAt()` 逐字符抽取真实字符：
- 反射调用 R8 无法优化；
- `CharSequence` 必然被混淆包装类实现（否则 Discord 自己也没法用这些字符串）；
- 对真正的 `String` 和混淆包装类都生效。
- 兜底再调 `toString()` + `PrintStream`。

**方案 B — `safeIsBlank()`（用于空白判断）**
绝不用 Kotlin 的 `isBlank()`。只用：
- `String::class.java.isInstance(value)`（反射，按运行时类型判断）；
- `String.length` / `charAt`（框架类，行为固定）；
- `Character.isWhitespace()`（框架 API）。

**方案 C — `safeGetString()`（读设置项）**
`getString()` 后在反射 `toString()` 结果上再走一次 `PrintStream` + `ByteArrayOutputStream` 做二次保险。

**方案 D — `LLMTranslator`/`LLMApiHelper` 构造参数用 `Any`**
接收 `Any`，内部再 `toRealString()`。这样 R8 不知道真实类型，不会提前做掉转换。

**记住三条铁律：**
1. 任何来自 `settings.getString(...)` 或 `message.content` 的值，**先用 `.toRealString()` 转成真 String，再做任何字符串操作。**
2. 判断空/空白用 `safeIsBlank(...)`，不要用 `.isBlank()` / `.isNotBlank()` / `.isEmpty()`——**包括对"看起来已经是真 String"的中间结果**（如 `Regex.replace(...)` 的返回值），R8 跨函数内联后仍可能把 `d0.d0.b` 喂给这些内联扩展导致 `IntIterator` 崩溃。
3. 往 `LLMTranslator` 传配置时传原始 `getString(...)` 并 `as Any`，不要提前 `.toString()`。

---

## 7. 已修复的关键 Bug（按时间，含最近这轮）

| 提交 | 问题 | 修复 |
|---|---|---|
| （历史）`2165162` | 长文本 LLM 报错 `closed` | 改用原生 `HttpURLConnection` 读 `errorStream` |
| `5b4d9db` | 译文显示错乱、缓存并发、自动翻译不稳 | 引入 `TranslateController` 线程池 + LRU 缓存 + pending 去重 + 编辑检测 |
| `37b3e40` | `getExecutor` 闭包变量 smart-cast 编译错误 | 调整局部变量声明顺序 |
| `4b7d541` | 翻译错误 Toast 无定位信息 | Toast 开头带 `Class.method:line`；`resolveBackend` 加 try-catch |
| `50eb0a7` | 仓库有自动推送工具残留 json | 删除 `batch_*.json` 等，加 README 和 .gitignore |
| `6331a41` | 对外部值调用 Kotlin `CharSequence` 扩展崩溃 | 全量改用 `toRealString`/`safeIsBlank` |
| `685ac0d` | `d0.d0.b` 转换 | `toRealString` 用 CharSequence 反射抽取 |
| `69fe54a` | 转换前就调用 String 方法 | 先 `String.format` 转真 String 再做操作 |
| `a4574f6` | `logCrash` 编译错误 | 修 import + 去掉歧义重载 |
| `5d4aad6` | 崩溃无法定位 | `logCrash` 写 `translate_crash.log` 且 Toast 前置定位 |

**最近这轮（2165162 → 6331a41）的核心改动总结：**
1. **并发模型从 `Thread{}.start()` 升级为 `ExecutorService` 线程池** + LRU 缓存 + pending 去重，避免每条消息建线程、避免重复翻译、控制内存。
2. **彻底解决 `d0.d0.b` 崩溃**：新增 `ResourceUtils.toRealString()`（CharSequence 反射抽取），并把所有外部值（消息内容、设置项）的处理统一走它 + `safeIsBlank`/`safeGetString`。
3. **无条件崩溃日志**：`DebugLogger.logCrash` 写 `translate_crash.log`，配合 Toast 前置崩溃位置，方便用户回传报错。
4. **自动翻译健壮性**：跳过已缓存/正在翻译的消息、失败计数、连续失败自动暂停、编辑消息自动失效旧译文。
5. **仓库清理**：删除自动推送工具残留的 json 文件，补上 README 和 .gitignore。

---

## 8. 构建与发布流程

- 本地：`./gradlew :TranslateEnhanced:make generateUpdaterJson`
- 自动：push `main` → GitHub Actions 构建 → 产物推到 `builds` 分支（`TranslateEnhanced.zip` + `updater.json`）。
- 用户侧更新源：`builds/updater.json`（在 `build.gradle.kts` 的 `updateUrl`/`buildUrl` 配置）。
- **插件描述**：zip 内 `plugin.json` 的 `description` 来自 Gradle 项目的 `description` 属性（Aliucord gradle 插件 `Tasks.kt` 生成 manifest 时取 `project.description`）。目前仓库没有设置，在 `TranslateEnhanced/build.gradle.kts`（子项目脚本）里加 `description = "..."` 即可。

---

## 9. 调试方法（交给用户/维护者）

1. 设置页开启 **Debug Mode**。
2. 复现问题。
3. 用文件管理器取出：
   - `/sdcard/Aliucord/translate.log`（统一日志：DEBUG 翻译详情 + WARN/ERROR 崩溃堆栈，带级别前缀）
4. Toast 报错现在会在最前面带 `@Class.method:line`，对照源码定位。
5. 设置页可一键"清除调试日志"。

---

## 10. 如何扩展

### 加一个翻译后端
1. 实现 `backend/TranslatorBackend.kt` 接口（`translate(text, sourceLang, targetLang): TranslateResult`）。
2. 在 `TranslateController.resolveBackend()` 增加分支。
3. 如需设置项，在 `Constants.kt` 加 `SETTINGS_KEY_*`，在 `PluginSettings.kt` 加 UI，在 `IStrings`/两份字符串加文案。
4. **注意**：配置值走 `as Any` 传入构造函数，内部 `toRealString()`，不要在外部 `.toString()`。

### 改 UI 文案
- 必须同时在 `IStrings.kt` + `StringsEn.kt` + `StringsZh.kt` 三处加同一字段，否则编译不过。

### 加新的清理规则
- 在 `TextCleaner.kt` 加正则 + 对应设置项开关。注意先 `toRealString()` 再处理。
- 所有清理项都走"占位符往返"：清理时用 `[[NAME_n]]` 替换并保存原值，翻译成功后由 `TextCleaner.restoreAll()` 统一还原（URL/emoji/标记三类已经接入）。

---

## 11. 已知问题 / TODO

- "译文和原文相同（原样返回）"问题：根因未定（可能是模型回显、温度 0、或文本本就在目标语言）。目前已有两个兜底手段：消息菜单"重新翻译"（强制重翻 + 防回显提示）和译文与原文相同时的 WARNING 日志。若需彻底定位，让用户开 Debug Mode 回传 `translate.log`。
- **译文渲染依赖 Discord 内部 API**：`buildTranslatedBuilder()` 用 `DiscordParser.parseChannelMessage` + 反射调用 `WidgetChatListAdapterItemMessage` 的 `getMessageRenderContext/getMessagePreprocessor/getSpoilerClickHandler` 重渲染译文（URL 可点击、emoji/提及/时间戳原生显示）；Discord 升级改签名时自动回退纯文本显示。
- 占位符（`[[EMOJI_n]]` / `[[TAG_n]]`）依赖翻译引擎原样保留：Google 通常保留括号 token，LLM 侧已把"保留占位符"写进 user prompt；若仍被改写，`restoreAll()` 会把缺失内容追加到译文末尾。URL 默认不占位，靠引擎保留 + `ensureUrlsPresent()` 补回。
- 设置页使用 Discord 原生组件（`CheckedSetting` 单选/开关、`UiKit_Settings_Item_Header/Icon` 样式、Discord 主题色），LLM 配置区随后端选择动态显隐。
- 大模型返回仅 `.trim()`，未处理模型可能夹带的 markdown/代码块包裹（如 ```json），如需可加后处理。
- 自动翻译依赖 `StoreStream.handleMessageCreate(api Message)`（store 层消息创建入口，比 WidgetChatList 稳定），Discord 升级仍可能改名/改参数，需重新适配。
- `patchProcessMessageText` 依赖 `SimpleDraweeSpanTextView.mDraweeStringBuilder` 字段名，Discord 升级可能改名。
- `forceRerenderMessage` 依赖 `WidgetChatList.access$getAdapter$p` 合成访问器，版本升级可能失效（已 try-catch 兜底）。
- `fetch_models` 仅支持 OpenAI 标准 `/v1/models`，非标服务商需自行适配。

---

## 12. 快速上手 checklist

- [ ] 装好 JDK 17 + Android SDK（compileSdk 31）。
- [ ] 能本地 `./gradlew :TranslateEnhanced:make` 出包。
- [ ] 读完第 6 节（R8 / `d0.d0.b`），这是本项目最容易再踩的坑。
- [ ] 改任何字符串处理前，确认值已 `toRealString()`，判断空用 `safeIsBlank()`。
- [ ] 改 UI 文案记得三处（`IStrings` + En + Zh）。
- [ ] 让用户开 Debug Mode 回传 `translate.log` 来报 bug。
