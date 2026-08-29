# ADR-005: Web 伴读服务（局域网网页阅读）

日期：2026-08-29
状态：已接受

## 背景

用户希望在电脑浏览器上阅读同 WiFi 下手机 App 书库里的书籍。手机是唯一书库与配置源（书籍、译文、进度都在 Room 库里），电脑端不导入、不管理。参考了两条既有路径：前身项目 vibe_reading（FastAPI + 无构建 Jinja/Alpine 网页，已验证「网页读本地书 + 流式翻译」交互）与 legado 的 Web 服务（NanoHTTPD + 前台 Service + assets 内 Vue SPA，完全无鉴权）。

## 决策

### 1. 形态：App 内嵌 HTTP 服务器 + 前台 Service

- 引入 **NanoHTTPD 2.3.1**（legado 同款，单 jar，Android 运行时无 JDK `com.sun.net.httpserver`），路由在 `serve()` 内分发，阻塞式线程模型。
- 服务器由独立前台 Service（dataSync 类型，对齐现有 `TranslationForegroundService` 模式）托管：通知栏展示含 Token 的服务地址，持 WifiLock/WakeLock，App 退后台/息屏后服务存活。
- 开关只在全局设置页（「Web 伴读服务」），展示地址 + 复制按钮；端口固定可配，默认 **9700**。
- 否决的替代方案：
  - Ktor Server CIO：协程路由优雅，但引入约 5MB 传递依赖，现有数据层 suspend 包一层即可桥接，不值。
  - 手写 ServerSocket：HTTP 解析/并发/分包全要自己来，不值。
  - 只在 App 前台时可用：与「人在电脑前、手机在旁边充电」的使用场景冲突。

### 2. 鉴权：随机 Token（否决无鉴权与密码）

- 服务每次开启生成随机 Token，地址形如 `http://IP:9700/?token=xxx`，复制地址即携带凭据；无 Token 请求一律 401。
- legado 的零鉴权意味着同网段任何人可翻书架、改它的库；Token 成本一个 header 校验，消掉该风险。
- 否决密码：多一个设置项多一份心智负担，Token 已覆盖威胁模型（家庭局域网、防蹭网窥探，不防定向攻击）。

### 3. 前端：assets 内无构建单页 HTML（否决 Vue SPA 与纯 API）

- 单页 HTML + 原生 JS/Alpine 风格，打包进 `assets/web/`，由服务器吐出。零 Node 构建链，风格对齐前身项目已验证的轻量做法。
- Web 端只做滚动阅读，**不移植分页引擎与 `CjkJustifier`**：两端对齐用 CSS `text-align: justify` 表达，段落结构由服务端下发的统一内容模型保证。
- 否决 Vue/Vite SPA：双语段落配对、点击展开原文等交互用几十行 JS 即可表达，不值得引入前端工程链。
- 否决纯 JSON API：没有可用的第三方前端，页面是需求的一部分。

### 4. 双语交互：点击段落展开原文（否决气泡移植）

- 对齐前身项目：点击正文段落在其下方展开该段中文侧文本（中文书=中文原文，英文书=中文译文），再次点击收起。不实现中文气泡（气泡是 App 触屏语境的叠加层设计）。
- 选词/查词/解释等 App 深度交互不在伴读端范围。

### 5. 进度：双向共享、段落级 offset（否决章节级与实时推送）

- 伴读端打开书籍先定位到手机当前 `ReadingPosition(chapterId, offset)`；阅读中按「视口顶部段落」的 `startOffset` 防抖回写 `lastReadChapterId + lastReadOffset`。
- 两端共享同一 Room 库，后写覆盖即语义，无合并问题；否决 WebSocket 实时跟读（无真实双端同时阅读场景）与章节级粗粒度（与 App 内细粒度进度不对齐）。
- 服务端用 `ReadingContent.fromChapter()` 下发带 `startOffset/endOffset` 的段落，offset 计算不出伴读端。

### 6. 翻译联动：Web 触发「开始/重试」，Coordinator 提升为进程级单例

- 伴读端可对 PENDING / FAILED / TOO_LONG 章节发起翻译（重试 = `resetChapter` + 重新开始）；可查看章节翻译状态。
- `TranslationCoordinator` 从 `ReaderViewModel` 提升为 Application 级单例，ViewModel 与 Web 服务注入同一实例——单任务状态机全局生效，`translationRunId` stale 防护不变。
- 否决 Web 端取消（场景少，App 内做更自然）与 Web 端 SSE 流式显示译文（只会在 Done 落库，看一半体验割裂）。
- 状态刷新用 HTTP 轮询（3~5 秒），否决 SSE/WebSocket 推送：NanoHTTPD 阻塞线程模型下长连接占用线程且需断连清理，轮询粒度对「看翻译进度」足够。

### 7. 服务共存与显示模式

- 伴读服务活跃时翻译不再单独启动 `TranslationForegroundService`（伴读服务的锁已覆盖保活）；伴读未开启时翻译走原路径。
- 伴读端可切换 `languageMode`（zh/en），走 `BookRepository.updateLanguageMode()` 持久化，App 端跟随；无译文段落的降级（单语回退）与 App 同语义。

## 后果

- 新增依赖仅 NanoHTTPD 一个；权限（INTERNET/WAKE_LOCK/前台服务）与 cleartext 已具备。
- App 现有代码唯一的结构性改动是 `TranslationCoordinator` 的归属提升，行为不变。
- 伴读端排版与 App 不逐像素一致（无分页、CSS justify），这是接受的取舍：伴读端的价值是「在电脑上接着读」，不是复刻 App 的排版保真度。
- 局域网明文 HTTP + Token 是接受的取舍；如未来需要公网访问，应走反代 + HTTPS，而非在 App 内做 TLS。
