# 译读 / VibeReading

**为英语学习而生的双语阅读器** — 导入 TXT/EPUB 书籍，逐章调用 LLM 生成译文（中文书译英、英文书译中），在中文/英文模式间自由切换。

**免费 · 无广告 · 简洁美观的现代界面** — 没有开屏、没有推送、没有打扰，只有你和书。

## 为什么选译读

- **英语学习利器** — 读英文原著时逐章 AI 中译、点击段落气泡对照原文；长按任意单词即查离线词典（ECDICT 50 万词条），还能让 AI 结合上下文解释单词与短语
- **简洁美观** — Modern Minimal 设计语言：白色大圆角卡片、柔和投影、iOS 风控件与三栏导航，留白与高级感并存的现代界面
- **完全免费** — 无内购、无订阅，翻译能力由你自己的 LLM API Key 提供，成本透明可控
- **无广告无追踪** — 纯本地应用：书库、进度、词典全在设备上，不收集任何数据

## 功能

- **TXT / EPUB 导入** — 通过系统文件选择器导入本地 TXT 或 EPUB 书籍，自动按章拆分；EPUB 正文插图内嵌展示，支持全屏预览（双指缩放）
- **LLM 逐章翻译** — 支持 DeepSeek / OpenAI 兼容 API，SSE 流式实时显示翻译进度；多配置档案切换，可配置 API Key、Base URL、模型名、章节字数上限、最大输出 Token 等
- **双语阅读** — 中文/英文双模式，方向随书籍原文语言；英文模式点击段落尾部气泡弹出中文侧文本（中文书=原文，英文书=译文）
- **查词与解释** — 长按选词、拖拽手柄扩展短语选区；内嵌 ECDICT 离线词典查词 + LLM 单词解释
- **五种翻页** — 滚动、平移、覆盖、无动画、仿真卷页（拖拽跟手 + 点按自动卷页）
- **排版引擎** — 基于 `TextMeasurer` 的行级整页排版，底部对齐，仿真卷页位图与 Compose 页视觉一致
- **阅读定制** — 字体/字号/行距/段距/页边距/字间距/首行缩进/两端对齐/5 种背景预设/夜间模式/单手模式
- **书架与统计** — 列表/网格书架，按最近阅读/书名/上传时间排序与搜索；统计页汇总双语覆盖率、阅读与翻译全景
- **局域网伴读** — 手机开服务，同一 Wi-Fi 下的电脑浏览器继续读，进度实时同步
- **主题系统** — 跟随系统/浅色/深色 × 黛蓝/苔绿/原木/藕荷/墨白强调色

## 截图

<table>
  <tr>
    <td><img src="docs/screenshots/bookshelf.png" width="200" alt="书架" /></td>
    <td><img src="docs/screenshots/reader-zh.png" width="200" alt="阅读器 · 中文模式" /></td>
    <td><img src="docs/screenshots/reader-toolbar.png" width="200" alt="阅读器 · 工具栏" /></td>
    <td><img src="docs/screenshots/settings.png" width="200" alt="设置" /></td>
  </tr>
  <tr>
    <td align="center">书架</td>
    <td align="center">中文模式</td>
    <td align="center">工具栏</td>
    <td align="center">设置</td>
  </tr>
</table>

## 技术栈

| 层 | 技术 |
|---|---|
| UI | Jetpack Compose + Material 3 |
| 架构 | 单 Activity，MVVM（手工依赖注入） |
| 本地存储 | Room + DataStore Preferences |
| 网络 | OkHttp + SSE（流式翻译） |
| 分页 | 自研行级排版引擎（`ChapterPaginator`） |
| 异步 | Kotlin Coroutines + Flow |

## 构建

### 环境要求

- **JDK 17**（必须；Android Studio 自带的 JBR/JDK 25 不适配当前 Gradle 配置）
- **Android SDK**：compileSdk 35，minSdk 26
- **Git**：版本号从 git tag 自动推导，确保仓库有 tag（如 `v0.1.6`）

### Debug 构建

```bash
./gradlew.bat :app:assembleDebug
```

Debug APK 输出：`app/build/outputs/apk/debug/app-x86_64-debug.apk`（按设备 ABI 选择）

### Release 构建

Release APK 需要签名。签名信息优先从环境变量读取（CI），其次从 `local.properties` 读取（本地）。

**1. 生成签名密钥（首次）**

```bash
keytool -genkey -v -keystore release.keystore -alias vibereading \
  -keyalg RSA -keysize 2048 -validity 10000
```

将生成的 `release.keystore` 放到项目根目录。

**2. 配置签名信息**

在项目根目录 `local.properties`（已 gitignore）中添加：

```properties
keystore.path=release.keystore
keystore.password=你的密钥库密码
key.alias=vibereading
key.password=你的密钥密码
```

**3. 打版本 tag**

```bash
git tag v1.2.3
```

tag 格式为 `v` + 语义版本号。构建时会自动将 `v1.2.3` 转为 `versionName = "1.2.3"`、`versionCode = 10203`。无 tag 时回退为 `0.0.0-dev`。

**4. 构建 Release APK**

```bash
./gradlew.bat :app:assembleRelease
```

输出按架构拆分（开启 ProGuard 混淆）：

```
app/build/outputs/apk/release/app-arm64-v8a-release.apk
app/build/outputs/apk/release/app-armeabi-v7a-release.apk
app/build/outputs/apk/release/app-x86_64-release.apk
app/build/outputs/apk/release/app-x86-release.apk
app/build/outputs/apk/release/app-universal-release.apk
```

## 单测

```bash
./gradlew.bat :app:testDebugUnitTest
```

## License

[CC BY-NC 4.0](https://creativecommons.org/licenses/by-nc/4.0/) — 自由使用、修改和分享，但不可用于商业目的。
