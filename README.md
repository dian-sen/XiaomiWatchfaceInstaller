# XiaomiWatchfaceInstaller

Android 表盘缓存提取工具。第一目标设备：**小米手环 9 Pro**。

**当前范围：只读提取 + 文件导入/导出 + 外部安装器交接。未实现本 App 独立 BLE/AuthKey 直连安装，不能宣称手环 9 Pro 已完成真机适配。**

## 功能

- Shizuku UserService 扫描中国版运动健康 `com.mi.health/files/WatchFace`。
- 兼容旧小米运动/Zepp Life 的 `com.xiaomi.hm.health/files/watch_skin_local`。
- 异步扫描，展示名称、字节数、修改时间、访问错误和扫描截断。
- 复制选中文件到应用私有目录；同目录临时文件、32 MiB 上限、失败清理，不修改官方缓存。
- 通过系统文档选择器导入可访问的文件；通过 CreateDocument 导出。
- 识别 Xiaomi BIN 头、9/12 位数字 ID、名称和 SHA-256；拒绝截断头作为可安装文件。
- FileProvider 以临时只读 URI 交给兼容安装器，例如 Gadgetbridge；安装器需自行配对、认证与确认。
- 无网络权限、无蓝牙权限、不自动取 AuthKey、不发送未经验证的蓝牙数据包。

## 使用

1. 安装并启动 Shizuku，授予本应用权限。
2. 在官方运动健康下载/试用目标设备表盘，然后扫描缓存。
3. 选择文件，读取和检查私有工作副本。
4. 导出文件，或交给已配对的兼容安装工具。

未知/截断格式会禁用外部安装交接。头部识别不代表机型、屏幕、资源编码或签名兼容，请只使用本人有权使用的目标型号资源。

## 权限与边界

Android 11+ 标准 SAF 不允许通用访问 Android/data；不能把文件夹授权作为可靠后门。可先在其他工具中将文件导出到 Downloads，再使用单文件导入。

Shizuku ADB 模式通常为 UID 2000，不等于 root，不能借此读取其他 App 的 `/data/user/0`。本项目不读取私有认证数据库或历史密钥日志。

缓存路径为经验与源码证据，不是厂商稳定 API。子目录层级、文件名和包格式会随版本变化，未下载、权限拒绝和格式不支持必须分别诊断。

## 构建与验证

要求 JDK 17、Android SDK Platform 35、Gradle 8.9。仓库提供 Gradle Wrapper：

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

默认本地 release 构建未签名，不用于直接安装；CI 使用独立生产签名，不使用 debug key。

验证分层：

- 14 项纯 JVM JUnit 用例：parser、ID 长度、截断、ZIP、SHA-256、文件复制上限及失败清理。
- Android Kotlin/Java/AIDL 编译检查。
- GitHub CI 完整单元测试、Lint 与 Debug/Release 打包。
- **没有手机或手环真机运行/刷写测试**。CI 成功不代表 BLE、OEM 文件权限或设备安装经过实测。

ARM64 本机构建可能遇到官方 SDK Linux 工具仅提供 x86-64 的限制；不要用过旧 AAPT2 替换后宣称完整验证通过。GitHub runner 使用 x86-64 官方工具链。

## 自动发布

- 每次 main 更新：测试、Lint、打包通过后发布唯一 `build-<run>-<commit>` 预发布版本。
- `v*` 标签：同样验证后发布正式版本。
- PR：只验证，不获取签名或发布。
- 手动 Actions：可构建并发布对应版本。
- Release 包含已签名 APK、SHA256SUMS.txt、BUILD-INFO.txt；任何校验失败不发布。

当前项目没有 native so，发布的是支持 ARM64 的 universal APK，而不是误标的 ARM64 专用 APK。

签名配置通过 GitHub Actions secrets：`ANDROID_KEYSTORE_BASE64` 与 `ANDROID_KEYSTORE_PASSWORD`；alias 为 `release`。签名材料不入 Git。初次生成的本地签名备份位于开发环境 `/root/.local/share/XiaomiWatchfaceInstaller/signing/`（权限受限）。维护者应将其备份到自己的安全存储；丢失密钥会影响后续原位升级。不要将该目录或密码公开。

## 后续适配

`ExperimentalBand9ProAdapter` 仅为接口边界，返回未实现错误。独立安装仍需型号/固件识别、AuthKey 会话、GATT 串行队列、protobuf、传输协商、分片校验及最终安装结果确认。

ID 改写默认不提供：0x28 是特定 BIN 格式的 ID 起点，但九字节规则非通用，改 ID 不能绕过密码学签名，也不能把其他机型表盘转为兼容资源。

## 资料与兼容性依据

- [Xiaomi-BiaoPan](https://github.com/wanfeng090525/Xiaomi-BiaoPan)：缓存路径、Shizuku 与 ID 处理参考。
- [Mi8WfBinTool](https://github.com/zhy8388608/Mi8WfBinTool)：二进制结构与设备差异。
- [Gadgetbridge](https://github.com/Freeyourgadget/Gadgetbridge)：设备支持、认证与安装器参考。本项目未复制其协议实现；若后续复用代码，必须遵守 AGPL-3.0-or-later。
- [BandBBS S3 教程](https://www.bandbbs.cn/threads/12810/) 与 [失败报告](https://www.bandbbs.cn/threads/15781/)：只作为历史路径证据，不等于 Band 9 Pro 验证。
- [Shizuku API](https://github.com/RikkaApps/Shizuku-API)、[Android SAF](https://developer.android.com/training/data-storage/shared/documents-files)：权限和接口依据。
- [AstroBox-Public](https://github.com/AstralSightStudios/AstroBox-Public)：公开代码缺少关键通信定义，不能直接当完整 SDK。
- [Mi-Fitness-Private-Server](https://github.com/octospacc/Mi-Fitness-Private-Server)：服务端资源分发，不是 BLE 安装 SDK。

本项目不是小米官方产品。表盘素材、厂商服务条款与第三方许可证仍须遵守；不提供绕过付费许可、签名或账户控制的功能。
