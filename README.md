# KeyRing

> 一个本地优先、以隐私与实用为核心的 Android 密码管理器。  
> A local-first Android password manager focused on privacy and practical usability.

## 快速导航 / Quick Navigation

- [中文版](#中文版)
- [English](#english)

---

## 中文版

### 项目定位

**KeyRing** 是一款运行在 Android 设备上的本地密码管理应用，用于集中保存常见账号信息（登录账户、用户名、密码、网站地址等），并提供快速检索、编辑、图片辅助区分、导入导出备份等能力。  
项目目标是：**离线可用、上手简单、兼顾安全与体验**。

### 核心功能

- **应用主密码保护**
  - 首次启动需设置主密码（确认输入）
  - 后续进入需解锁
  - 支持修改应用主密码
- **生物识别解锁**
  - 可在设置中启用/关闭
  - 启用时先校验主密码
  - 使用 Android Keystore + BiometricPrompt 做安全解密
- **密码条目管理**
  - 新增、查看、编辑、删除
  - 字段包含：标题、登录账户、用户名、密码、确认密码、网站地址、描述、标签
- **密码生成器**
  - 在设置页配置长度与字符集（大小写/数字/符号）
  - 新增/编辑页可一键生成并填充
- **网站图标（Favicon）**
  - 在新增/编辑页可从网站获取图标作为条目头像
  - 设置中有“允许使用网络”开关（默认关闭）
- **图片与头像**
  - 支持相册选图作为头像
  - 支持添加多张附件图片
- **搜索与排序**
  - 支持关键字搜索
  - 支持按名称/时间升降序
- **备份能力**
  - 支持导出数据
  - 支持导入数据
- **局域网同步**
  - 同一 Wi-Fi 下设备间点对点加密同步
  - 支持双向数据合并（拉取并推送）
  - 基于配对码与信任密钥的安全传输
  - 可配置重复条目合并策略
- **关于页信息完善**
  - 开源依赖、图片素材引用、免责声明、版权声明
- **多语言**
  - 跟随系统 / 简体中文 / English

### 安全与隐私说明

- 数据默认存储在本机（本地优先）
- 应用主密码以哈希方式处理，不存明文
- 生物识别能力基于系统安全组件（Keystore + Biometric）
- 仅当用户主动开启网络并使用网站图标功能时，应用才会访问对应网站获取图标

### 技术栈

- Kotlin
- Jetpack Compose + Material 3
- Navigation Compose
- Room + KSP
- AndroidX Security Crypto
- Biometric
- Coil
- Ktor (网络通信框架，用于局域网同步)
- Kotlinx Serialization (数据序列化，用于同步传输)

### 构建与运行

#### 环境建议

- Android Studio（较新稳定版）
- JDK 11
- Android SDK（minSdk 29）

#### 常用命令

```bash
# Debug 构建
./gradlew :app:assembleDebug

# Release 构建
./gradlew :app:assembleRelease
```

> Windows 可用 `gradlew.bat`。

### 包体积优化（已完成）

- 已启用 `release` 的 R8 代码压缩与资源裁剪
- 已限制打包语言资源为中英两套
- Release 包体积已从约 49.6MB 优化到约 6.2MB（随版本略有波动）

### 路线图（建议）

- 问题反馈支持一键发邮件/复制邮箱
- 语言包补全（全量英文文案）
- 数据加密备份策略增强
- UI 细节与平板适配

---

## English

### Purpose

**KeyRing** is a local-first Android password manager designed to store and manage account credentials securely and efficiently on-device.  
The project focuses on **offline usability, privacy, and clean user experience**.

### Key Features

- **Master password protection**
  - Set on first launch
  - Required on app unlock
  - Change master password from drawer menu
- **Biometric unlock**
  - Optional in settings
  - Requires master-password verification before enrollment
  - Backed by Android Keystore + BiometricPrompt
- **Password entry management**
  - Create, view, edit, delete entries
  - Fields: title, login account, username, password, confirm password, website URL, description, tags
- **Password generator**
  - Configurable length and charset types
  - One-tap generation in add/edit screens
- **Website favicon support**
  - Fetch website icon as entry avatar
  - Controlled by a dedicated network toggle (off by default)
- **Images and avatars**
  - Custom avatar from gallery
  - Multiple attachment images
- **Search and sort**
  - Keyword search
  - Sort by name/time in ascending/descending order
- **Backup**
  - Export data
  - Import data
- **LAN sync**
  - Peer-to-peer encrypted sync between devices on the same Wi-Fi
  - Bidirectional data merging (pull then push)
  - Secure transmission with pairing code and trust key
  - Configurable duplicate entry merge policy
- **About page**
  - Open-source libraries, image attributions, disclaimers, copyright notes
- **Language options**
  - System default / Simplified Chinese / English

### Privacy & Security

- Local-first data storage
- Master password handled as hash (no plain-text storage)
- Biometric flow protected with Android platform security components
- Network is only used when explicitly enabled and when favicon fetch is requested

### Tech Stack

- Kotlin
- Jetpack Compose + Material 3
- Navigation Compose
- Room + KSP
- AndroidX Security Crypto
- Biometric
- Coil

### Build

```bash
# Debug
./gradlew :app:assembleDebug

# Release
./gradlew :app:assembleRelease
```

Use `gradlew.bat` on Windows.

### APK Size Optimization

- Release minification and resource shrinking are enabled
- Packaged locales are restricted to Chinese and English
- Release APK size has been reduced significantly (roughly from ~49.6MB to ~6.2MB)

---

## License

当前仓库暂未附带开源许可证文件（`LICENSE`）。  
请在对外发布前根据你的分发计划补充许可证条款。

