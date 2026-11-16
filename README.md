# Watchface Studio · XiaomiWatchfaceInstaller

基于 **OronBox · OrPudding · https://github.com/OronCanopy/OronBox** 的独立修改版，面向小米与华米穿戴设备的本地表盘工作台。本项目由 dian-sen 维护，不是 OronBox、小米或华米的官方版本。

当前重构版本为 `0.8.0+13`，Android 包名保留 `dev.xiaom.watchfaceinstaller`。原 Kotlin 工程完整保留在 [`legacy-android/`](legacy-android/)；新应用使用 Flutter/Riverpod 与已有设备协议，Android 原生层负责受限缓存读取和 Root 密钥快照。

已发布通过完整验证的 **[ARM64 APK · build-24-bb70f27](https://github.com/dian-sen/XiaomiWatchfaceInstaller/releases/tag/build-24-bb70f27)**，最低 Android 8.0。此包签名与旧版相同；下载后独立验签、ZIP/ELF 和实际 ARM64 架构检查通过。SHA-256：`cbc9fa7aa9932c1063defc02d603c34e3c4fd12af7f07075ffbf6ce46f92145d`。完整记录见 [`docs/refactor-verification.md`](docs/refactor-verification.md)。真机登录、Root/Shizuku 缓存访问和手环安装仍待实测。

## 功能与入口

| 入口 | 功能 |
| --- | --- |
| 工作台首页 | 未连接设备也可进入表盘工具；查看连接状态并选择设备 |
| 小米 / 华米账号 | 保留 OronBox 的小米账号登录、二次验证、会话刷新、设备同步及华米 / Zepp 账号登录 |
| 设备密钥 | 小米账号同步、官方穿戴日志导入、手动添加设备、Root 读取 n67cn 数据库快照 |
| 本地安装 | 保留小米 / ZeppOS 设备连接、本地文件确认和安装队列；安装需要连接与协议就绪 |
| 表盘工作台 | 导入 BIN / ZIP、Root / Shizuku 扫描、读取名称和预览、重命名、编辑 ID、导出 BIN / 预览 / ZIP |

界面采用 ColorOS 17 风格的视觉设计：浅灰背景、白色圆角分组卡、大标题、蓝紫色强调和胶囊选择器；支持深色与 OLED 模式。这是风格实现，不依赖 OPPO 私有组件。

社区资源库、创作者、社区账号、公告、在线推荐、插件商店和上游自动更新默认隔离。官方小米／华米账户请求、设备连接和本地文件处理保留。

## 设备账号与密钥

在首页选择 **小米 / 华米账号**，或者进入 **设备密钥**：

1. **小米账号同步**：登录小米账户，按提示完成二次验证，同步绑定设备；服务端没有返回可用密钥时不会伪造记录。
2. **官方穿戴日志**：从官方应用导出日志后选择导入，解析支持的小米 / 华米设备记录；地址不完整或存在歧义时需要先确认设备，不能仅凭地址后缀猜测。
3. **Root 读取**：仅在点击后运行，当前固定支持中国版运动健康 `com.mi.health` 的小米手环 9 Pro `miwear.watch.n67cn`。只读复制数据库和 WAL 到私有临时目录，稳定性和 SQLite 检查通过后只读取 `encrypt_key`。临时快照随后删除。
4. 明确选择一条设备并确认导入，密钥才进入系统安全存储中的配对记录；不会因此自动连接、安装或上传。

Root 候选列表只显示掩码。离开页面、进入后台或取消时清除临时记录并取消任务。设备信息和只读信息接口不展示 AuthKey 明文。若选择记住账号密码，凭据进入系统安全存储，普通偏好设置中的旧副本在迁移后清除。

Root 权限属于本应用自身；Minis、ADB 或其他应用拥有权限不等于本应用已获准。Shizuku 的 ADB 身份不能读取其他应用的内部私有认证数据库。

## Root / Shizuku 提取缓存

进入 **表盘工作台 → 缓存访问方式**：

1. 选择 **Root** 或 **Shizuku**。
2. 点击 **授权并检查访问**，按系统或 Root 管理器提示授权；Shizuku 模式需先启动 Shizuku。
3. 点击扫描，选择候选 `resource.bin`，等待生成私有副本。
4. 核对 BIN ID、名称、预览和警告，再重命名或修改 ID。
5. 导出 BIN、预览图或包含当前副本与预览的 ZIP。源缓存始终只读。

固定扫描根目录：

- `/storage/emulated/0/Android/data/com.mi.health/files/WatchFace`
- `/storage/emulated/0/Android/data/com.xiaomi.hm.health/files/watch_skin_local`

Shizuku 使用独立 UserService，在实际 UID 0 或 UID 2000 下执行同一白名单读取。权限不足、路径缺失、链接拒绝和达到限额分别返回诊断。UID 2000 不等于 Root，能否读取官方缓存取决于 Android 与 OEM；不会通过 chmod、修改 SELinux 或写入官方目录绕过限制。

读取限制包括 BIN 32 MiB、单张预览 8 MiB、预览总量 16 MiB、XML 512 KiB，并校验路径、符号链接、打开后的文件描述符、输出大小和时间。候选路径及 BIN 头不构成设备兼容性认证。

## ADB 提取缓存

当前 ADB 路径是 **电脑导出 → 应用导入**，不包含应用内无线 ADB 配对。

电脑安装 Python 3 和官方 Android platform-tools，在手机完成调试授权后执行：

```sh
python3 tool/adb_watchface_export.py --list
# 多设备时增加 --serial SERIAL
python3 tool/adb_watchface_export.py --serial SERIAL --list
python3 tool/adb_watchface_export.py --serial SERIAL \
  --path '/storage/emulated/0/Android/data/com.mi.health/files/WatchFace/候选目录/resource.bin' \
  --output face.zip
```

将 `face.zip` 传回手机，在工作台选择 **ADB 导出包 → 导入 ADB 缓存包**，或直接从本地文件入口导入 ZIP。脚本只读固定目录，不覆盖本地已有导出，不执行配对、提权或设备写入；系统拒绝访问时会明确报错。

导出包包含 BIN、可读取的 XML / PNG / JPEG 和逐文件长度、SHA-256 清单。应用在内存中校验 ZIP 路径、重复项、符号链接、加密条目、长度、CRC、SHA-256 和总解压预算后导入。没有预览仍可导出 BIN。工作台自身导出的 BIN+预览 ZIP 也可重新导入。

## 重命名、ID 与安装

- 名称用于导出文件名，支持中文，过滤分隔符、控制字符和保留名称，不改写不透明 BIN 内容。
- 支持已识别完整 Xiaomi BIN 的 9 / 12 位数字 ID。等长修改仅更改 ID；扩展到 12 位时要求目标区域全部为零填充，拒绝覆盖其他字节。
- 编辑始终创建内存副本，原文件和官方缓存不变。ID 修改不修复签名、校验或不同机型资源，也不能保证设备接受。
- 安装继续执行原始 ID 和当前 ID 的已有策略，不因重命名或改 ID 清除限制。
- 连接就绪、加入队列或上传 100% 均不直接等于设备安装成功。真实型号 / 固件兼容性需要对应真机验证。

## 构建与验证

固定 Flutter `3.47.9`（`.fvmrc`，提交 `c197ca5199e9ee5661e865596c9c07294dfab51c`）、JDK 17。Android 需要 Platform 36 / 35、Build Tools 36.0.0、NDK 28.2.13676358、CMake 3.22.1。XMS 子模块固定 `6483f939785e9c1dd011465d573931f669a6adab`。

```sh
git submodule update --init --recursive
flutter pub get --enforce-lockfile
bash tool/ci_verify.sh
# 已准备 Android SDK 的宿主：
bash tool/ci_verify.sh --android
```

Release 只使用现有签名配置，不回退 debug key。沿用仓库 Secrets `ANDROID_KEYSTORE_BASE64` / `ANDROID_KEYSTORE_PASSWORD`，alias 为 `release`；也支持受限的本地 `android/key.properties`。保持相同包名和证书才可覆盖升级，不要通过卸载来掩盖签名不一致。

GitHub Actions 保留验证后发布行为：PR 只验证；main 更新生成唯一预发布 Release；`v*` 标签生成正式 Release。自动构建固定 ARM64，并在原生插件和应用打包处同步过滤 ABI。产物包含 ARM64 签名 APK、SHA256SUMS 和构建信息。APK 构建、签名、安装及真机兼容性须分别以实际记录为准，不能用单元测试替代。

## 来源与许可

本重构基于私有 OronBox 的 `706fe87` 自用核心，并保留已有 XiaomiWatchfaceInstaller 的 Root 快照设计。对应来源和修改见 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)、[`ADDITIONAL-TERMS.md`](ADDITIONAL-TERMS.md) 与 [`docs/oronbox-source.txt`](docs/oronbox-source.txt)。保留 OronBox、OrPudding、Gadgetbridge、AstroBox 等上游署名与许可证。

本项目按 AGPL-3.0 分发，完整许可见 [`LICENSE`](LICENSE)。应用内“关于软件”保留来源文字署名。官方账号服务和缓存布局并非稳定公开 API，后续版本变化可能需要适配。
