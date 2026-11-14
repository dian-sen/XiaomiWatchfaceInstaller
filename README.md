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

## 使用（0.2.0：Root 优先）

1. 有 Root 的手机无需安装 Shizuku。保持默认 Root 模式，点击“授权 / 检查 Root”，在 Magisk/KernelSU/APatch 管理器中允许本应用。实际执行 `su -c 'id -u'` 确认 UID 0，不根据 su 文件存在推断授权成功。Eta 的 Root 权限不等于本应用权限。
2. 授权成功后点击“扫描缓存”。只读访问固定的两个缓存根目录；不存在的旧版路径会显示诊断，不代表主目录扫描失败。
3. 选择 `resource.bin`，读取并检查私有工作副本。扫描也可能出现 resources 内的其他 BIN，它们不一定是完整表盘。
4. 导出文件，或交给已配对的兼容安装工具。当前不实现独立蓝牙安装。

无 Root 时才选择可选的 Shizuku 模式；不安装 Shizuku 也可以通过系统文件选择器导入/导出。Root 拒绝或超时会显示错误，不自动修改 Root 管理器策略或 SELinux，不通过 chmod 更改缓存。

Root 命令有白名单、规范路径检查、符号链接拒绝、NUL 分隔元数据、输出上限、20秒超时与失败清理。源文件只读，副本由本应用普通进程写入自身私有目录；不会用 Root 改写官方缓存或读取 AuthKey。

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

- 54 项纯 JVM JUnit 用例：原有14项 parser/文件复制测试，加40项 Root 路径策略、协议解析、超时/中断、stderr/exit 失败、特殊文件名及失败清理测试；使用模拟进程，不向系统授予权限。
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

## 0.3.0 缓存筛选与交接说明

默认仅显示候选：文件名 `resource.bin` 且不在 `resources`/`_lua` 子目录；可切换全部 BIN、候选、资源/其他 BIN，也可按文件名或路径关键词过滤。此规则是路径启发式，绝不等价于完整表盘、机型匹配或签名校验。列表显示字节数和修改时间。

名称采用严格 UTF-8，再尝试可往返的 GB18030/GBK；未知变体或非法字节显示“未识别”，不输出替换乱码。实际样本的固定名称位置为 FF FF FF FF，不能假称其包含可恢复名称。

外部交接提供 ACTION_VIEW（打开）和 ACTION_SEND（分享）两条 URI 入口，均授予临时读取权限。接收 App 必须实现对应导入，出现首页/要求重选文件不算直接导入完成；可以改用另一方式或手动导出。当前仍不直接连接手环、不提供 ID 改写、不执行安装推送。

手机实测：普通应用身份读取官方 Android/data 缓存返回 Permission denied，Root 读取成功。此处是别的 App 的外部专属目录，不是无条件可读公共存储，也不是 /data/user/0 内部私有目录。Downloads 中的文件通过 SAF 导入不需要 Root。

## 0.4.0 XML 名称与元数据

候选 `resource.bin` 优先读取同目录 `description.xml` 的 `watch/name`；缺失或无法使用时读取 `resources/manifest.xml` 的直接名称或 `@watchfaceName` 翻译，语言顺序 zh_CN、zh_TW、en_US。

列表采用 XML 名称前核对缓存路径 ID；选择文件后重新以 BIN 头 ID 核对，不一致则警告并拒绝采用 XML 名称。列表可按中文名称搜索，详情保留 BIN 解析名与 XML 名称来源，显示设备和尺寸声明（不是兼容性认证）。单独导入的 BIN 没有旁边 XML 时仍可能显示未识别；不会凭空恢复名称。XML 元数据目前仅保存在当前会话，不随单独 BIN 导出。

XML 读取固定白名单路径、只读、512 KiB 上限；禁止 DTD 和外部实体。纯 JVM 共84项测试通过；安全限制及解析优先级有专门测试。Root 与 Shizuku 均提供固定 XML 读取入口，不开放任意路径读取。

## 0.5.0 同名导出、预览与 ID 副本

- 导出名称优先用 XML 表盘名，保留中文并过滤路径分隔符、控制字符及保留名称。
- 单 BIN 或 BIN+预览通过 SAF 选择普通目标目录。重名时统一加序号，不覆盖已有文件；同一组保持同一基础名，例如“流沙 2.bin”和“流沙 2.png”。
- 预览优先来自与 BIN ID 一致的 manifest Theme/Image 引用，或固定的 `preview/preview.png`、`resources/_preview` 白名单。只读取 PNG/JPEG，最多8 MiB，不信任任意 XML 文件路径。缺图明确仅导出 BIN。
- 内部“ID 修改/连接诊断”页可生成等长9/12位ID副本，不覆盖原文件；返回后主界面重新校验，导出名附 `_ID-新ID`。保留原 XML 名称但明确标记 ID 已改、原XML未重新认证；不声称修改ID可绕过签名。
- 新增用户主动触发的15秒 BLE 扫描和服务发现诊断。不会后台自动扫描、索取密钥或上传表盘。
- **9 Pro 直接认证与刷写仍未完成，安装入口关闭。** 上游默认 Classic，不能直接套用其他设备 BLE；见 docs/BLE-SOURCES.md 固定源码、协议及阻塞说明。离线协议代码不是实际适配承诺。
- 当前版本包含 Gadgetbridge 派生协议研究代码，按 AGPL-3.0-or-later 分发，原作者声明见 docs/BLE-NOTICE.md 和源文件。完整许可证见 LICENSE。

新增蓝牙权限仅在用户主动诊断时申请；缓存扫描、导出、ID修改不需要蓝牙权限。此前“无蓝牙权限”和“没有ID修改”的版本描述仅适用于0.4及以前。所有发布仍经过单测、Lint、签名打包；未执行真实手环刷写测试。

## 0.5.1 ID 修改反馈修复

修复 Android 应用根目录合法别名（如 /data/data 与 /data/user/0）导致返回副本被误拒的问题。以 canonical 私有根目录为信任锚，仍拒绝根目录以下符号链接、越界与 .. 路径。当前 BIN ID、原 XML ID 明确展示，可点击重读；加载与修改期间禁用重复操作，成功显示旧ID→新ID及SHA，失败显示状态和Toast。新增9项路径测试，全量124项离线测试通过。此版本不增加密钥提取或手环刷写；原有直装限制仍有效。
