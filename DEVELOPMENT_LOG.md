# MyPasswords 开发日志

本文档记录本项目从初始搭建到当前功能的主要开发过程，便于回顾与交接。

---

## 阶段一：项目起步

- 使用 **Android Studio** 创建基础工程，**Jetpack Compose** + **Material 3**。
- 主页展示 **「Hello Android」**，作为运行验证。

---

## 阶段二：应用锁与首次设置

- **首次使用**：引导用户设置应用密码（至少 4 位，含确认）。
- **再次进入**：需输入密码解锁后才能进入主页。
- **实现要点**：
  - `PasswordStore`：使用 **EncryptedSharedPreferences**（`androidx.security:security-crypto`）存储密码 **SHA-256 哈希**，不落库明文。
  - 界面：`SetPasswordScreen`、`UnlockScreen`，文案以中文资源为主。
  - 主流程：`MyPasswordsApp` 中根据是否已设置密码在「加载 / 首次设置 / 解锁 / 主页」间切换。

---

## 阶段三：主页框架与修改密码

- **顶部导航**：左侧 **菜单（汉堡图标）**，打开 **左侧抽屉**。
- **抽屉**：提供 **「修改密码」**；成功后要求 **重新登录**（回到解锁页）。
- **依赖**：`material-icons-extended`；图标曾用 `Icons.AutoMirrored.Filled.Menu`，后因环境兼容性改为 **`Icons.Filled.Menu`**。

---

## 阶段四：密码条目核心功能（数据 + 列表 + 新增）

- **目标**：作为密码管理器，保存多组账号信息。
- **持久化**：**Room**（`PasswordEntry` 实体、`PasswordEntryDao`、`AppDatabase`），**KSP** 生成代码。
- **依赖**：Navigation Compose、Coil（图片）、Lifecycle Runtime Compose（列表收集 Flow）等。
- **应用类**：`MyPasswordsApplication` 提供数据库与 `PasswordEntryRepository`。
- **主页列表**：`PasswordListScreen`，按 `updatedAt` 倒序展示。
- **新增页**：`AddPasswordScreen`，字段包括标题、账户名、用户名、密码、网址、描述；高级项为 **标签**（对话框）、**附图**（系统选图 + 复制到应用目录）、**头像**（首字/选图，见下）。
- **实体演进**：
  - 增加 `avatarImagePath`（与「附图」区分）。
  - 增加 `updatedAt`，数据库版本迁移（如 `updatedAt` 自 `createdAt` 回填）。

---

## 阶段五：列表与头像展示优化

- **列表项**：整行可点；**左侧** 圆形头像（首字 + 背景色或自定义头像图）；**中间** 标题 + **单行省略** 描述；**右侧** **相对时间**（刚刚 / N 分钟前 / N 小时前 / N 天前）。
- **添加页头像**：标题输入框 **上方居中**，大圆头像；默认 **标题首字 + 彩色背景**；右下角 **小编辑圆钮** 换头像图。
- **工具类**：`RelativeTimeFormatter`、`ImageStorage`（URI 复制到私有目录）等。

---

## 阶段六：详情页与编辑

- **导航**：`detail/{entryId}`、`edit/{entryId}`。
- **详情页** `PasswordEntryDetailScreen`：
  - 顺序：**图标、标题、描述**；其余字段用 **横线 + OutlinedCard** 分区，带 **复制** 与 Toast 提示。
  - 有附图时展示图片。
  - 底部 **分隔线** + **右对齐小字**：绝对时间 + 相对时间（如「上次更新时间 yyyy-MM-dd HH:mm（N 天前）」）。
- **编辑**：复用 `AddPasswordScreen`，传入 `entryId` 加载并 **update**；替换旧图片文件时做清理。
- **顶栏**：详情为 **返回 + 标题「查看」+ 编辑**；编辑为 **返回 + 标题「编辑」**。

---

## 阶段七：交互与体验微调

- **详情页底部分隔线**：由单独加粗改为 **普通 `HorizontalDivider()`**。
- **保存入口**：新增 / 编辑页 **底部保存按钮移除**，改为 **顶部导航栏右侧「保存」**（`SideEffect` 注册保存逻辑到 `MainHomeScreen`，`DisposableEffect` 注销），避免用户找不到底部保存。

---

## 阶段八：设置中心与安全能力增强

- **设置页完善**：按「外观 / 安全 / 列表 / 网络 / 密码生成器」分区。
- **生物识别解锁**：
  - 支持在设置中启停；
  - 首次启用需先验证应用主密码；
  - 使用 Android Keystore + BiometricPrompt 进行密文保护与解锁。
- **排序配置**：密码列表支持名称/时间升降序，并持久化到 `AppPreferences`。
- **自动锁定**：离开应用或熄屏后触发重新解锁。

---

## 阶段九：密码生成器与新增/编辑流程升级

- **密码生成器**：
  - 在设置页可配置长度、字符集（大小写/数字/符号）；
  - 在新增/编辑页密码框右侧可点击「生成密码」自动填充。
- **密码输入体验**：
  - 新增「确认密码」输入框；
  - 支持明文/密文切换；
  - 保存前校验两次密码一致性。
- **字段与顺序调整**：
  - 「账户名」统一为「登录账户」；
  - 表单顺序调整为标题后紧跟网站地址等关键字段。

---

## 阶段十：网站图标、关于页与抽屉信息化

- **网站图标功能**：
  - 新增/编辑页支持「使用网站图标」与「使用默认图标」；
  - 通过 URL 获取 favicon 并保存为头像；
  - 增加网络开关（默认关闭），未开启时弹窗引导前往设置。
- **头像可见性优化**：针对白色/浅色图标加底色与描边，避免看不见。
- **关于页内容升级**：
  - 调整章节顺序；
  - 补充网络能力声明、免责声明细化；
  - 更新开源依赖列表并加“最后更新时间”；
  - 增加“引用的图片素材”与版权限制条款。
- **抽屉增强**：
  - 顶部下方新增统计卡片（条目总数、上次解锁时间）；
  - 新增「问题反馈」「语言/Language」入口。

---

## 阶段十一：多语言、机型兼容与视觉收尾

- **多语言切换**：
  - 新增语言页（跟随系统 / 简体中文 / English）；
  - 语言配置持久化并在 `attachBaseContext` 级别生效；
  - 增加 `values-en` 英文资源（覆盖高频页面文本）。
- **Android 14 指纹异常修复**：
  - 修正 `setUserAuthenticationParameters` 参数顺序，解决部分设备“非法状态”问题。
- **抽屉在短屏可用性**：
  - 抽屉宽度改为按屏幕比例+上限；
  - 抽屉内容改为可滚动；
  - 最后一项后增加底部留白，提升短屏可达性。
- **图标适配收尾**：
  - 应用名改为 **KeyRing**；
  - 更换启动图标并加入安全边距，减少系统遮罩裁切导致的图标缺失。

---

## 阶段十二：安装包体积优化（Release）

- **问题现象**：Release 安装包在优化前约 **49.6 MB**（`app-release-unsigned.apk`）。
- **为什么优化前体积大**（核心原因）：
  - `release` 未开启代码压缩（`isMinifyEnabled=false`），R8 未参与删除未使用代码；
  - 未开启资源裁剪（`isShrinkResources`），未引用资源与依赖资源被整体打包；
  - 未限制语言资源，依赖库携带的多语种字符串会被一起打入 APK；
  - 项目依赖栈较完整（Compose / Material3 / Navigation / Room / Security Crypto / Biometric / Coil），在“无裁剪”前提下体积累积明显。
- **本次优化措施**：
  - 在 `build.gradle.kts` 的 `release` 启用：
    - `isMinifyEnabled = true`
    - `isShrinkResources = true`
  - 在 `defaultConfig` 增加 `resourceConfigurations += listOf("zh-rCN", "en")`，仅保留中英资源。
  - 按 AGP 生成的 `missing_rules.txt` 补充 `proguard-rules.pro`（`-dontwarn com.google.errorprone.annotations.*`），保证 R8 可正常执行。
- **优化结果**：Release 包从约 **49.6 MB** 降到约 **6.2 MB**。

---

## 阶段十三：局域网点对点同步（配对码 + 加密 + NSD）

- **数据**：`PasswordEntry` 增加 **`uuid`** 字段（Room 迁移 4→5，历史数据补齐随机 UUID）；备份 JSON 同步写入 `uuid`。
- **传输与安全**：配对码 + 双方盐经 **PBKDF2** 派生会话密钥，再用 **HKDF（字典序 deviceId）** 派生长期信任密钥；`/sync/pull`、`/sync/push` 载荷经 **AES-GCM** 加密。
- **合并**：`MergeEngine` — **UUID 优先**，否则 **标题/账户/用户名/URL 指纹**；策略：**跳过 / 较新覆盖（默认）/ 保留两份（标题加副本）**。
- **服务**：嵌入式 **Ktor CIO** HTTP 服务（`SyncServer`），`SyncClient` 拉取/推送 ZIP 归档（`SyncArchive`）。
- **发现**：**NSD** 广播/发现 `_keyring._tcp.`，`SyncScreen` 展示可点击填入 IP。
- **界面**：抽屉 **「局域网同步」**；设置中 **同步重复策略**；关于页补充说明。
- **构建**：R8 对 Ktor 缺失类补充 `-dontwarn`（`java.lang.management.*`、`slf4j`）。

---

## 技术栈摘要

| 类别 | 选型 |
|------|------|
| UI | Jetpack Compose, Material 3 |
| 导航 | Navigation Compose |
| 本地库 | Room + KSP |
| 安全 | EncryptedSharedPreferences，密码哈希 SHA-256 |
| 图片 | Coil，系统 Photo Picker |
| 语言 | Kotlin |
| 局域网同步 | Ktor Server/Client CIO，Kotlinx Serialization，AES-GCM，NSD |

---

## 已知注意点

- IDE 偶发 **Unresolved reference navigation** 等提示，若 **Gradle 能正常编译运行**，多为索引/同步问题，可尝试 Sync、Invalidate Caches。
- Kotlin / KSP 版本需与工程 Gradle 配置一致，升级时注意 Room、KSP 兼容性。

---

*文档随项目迭代可继续追加章节（如：导出备份、生物识别、多主题等）。*
