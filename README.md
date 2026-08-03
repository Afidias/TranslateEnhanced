# TranslateEnhanced

Aliucord 翻译插件：Google Translate 与 OpenAI 兼容大模型双后端，支持按频道自动翻译。

## 功能

- 双后端：Google Translate / LLM（OpenAI 兼容接口），LLM 失败自动降级 Google
- 按频道开启自动翻译，连续失败自动暂停，菜单可一键恢复
- 翻译前清理 HTML / URL / Emoji（可在设置中关闭）
- 消息右键菜单：翻译 / 显示原文
- `/translate` 斜杠命令
- 调试模式：记录详细日志到 `/sdcard/Aliucord/translate.log`（崩溃日志也写入同一文件）

## 构建

```bash
./gradlew :TranslateEnhanced:make generateUpdaterJson
```

构建产物在 `TranslateEnhanced/build/` 下，将 `TranslateEnhanced.zip` 放入 `/sdcard/Aliucord/plugins` 即可安装。

## 使用

1. 安装 Aliucord 与 Discord 126021
2. 在插件设置中配置后端（Google 或大模型）与默认目标语言
3. 在频道消息菜单中开启「自动翻译此频道」，或手动翻译单条消息
