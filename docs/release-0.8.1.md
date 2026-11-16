# Watchface Studio 0.8.1（14）

## 行为变化

- 本地安装、表盘工具、Root/Shizuku 缓存表盘和 ADB ZIP 导入统一支持导入时选择“保留原 ID / 修改随机 ID / 取消”。
- 随机 ID 实际写入 BIN；应用私有导入槽位原子替换并读回校验，随后用新 BIN 重新识别、执行安装限制和入队。后续手动编辑也先保存替换再检测安装。9 位 ID 默认随机为 9 位，12 位随机为 12 位，避免无必要扩展字段。
- 只覆盖本应用拥有的导入副本。文件选择器原文件与运动健康缓存源保持只读。取消 ID 弹窗会中止本次导入；取消后续安装确认保留已修改的导入副本，重试不再次生成 ID。
- 缓存候选严格只显示 `resource.bin`；`1.bin`、其他名称及 `RESOURCE.BIN` 均不显示。原生扫描在候选计数之前筛选，Dart 解码、列表与选择入口另有一致检查。
- 恢复 GitHub MiWearFirmwareArchives 固件目录及 Releases 链接。支持分页查询、机型/地区匹配、最新与历史完整包下载；所有在线操作均选择 `_full_` 包，不自动选增量包。下载长度、256 MiB 上限及源提供的 SHA-256 校验通过后，才出现安装确认。
- 固件页面关闭会取消下载；下载期间切换设备时拒绝入队，安装确认再次检查目标设备，安装队列固定目标地址。未经过设备实际刷写验证。
- 设置 → 应用数据新增“导入及修改后的表盘”和“表盘预览图”，显示占用大小、支持确认后独立清理。账号、AuthKey、配对设备、固件与安装队列持有的副本不在清理范围内。

## 存储路径核实

`getApplicationSupportDirectory()` 在已锁定的 `path_provider_android 2.3.1` 中对应 Android `Context.filesDir`。旧 Kotlin 版也直接使用 `File(filesDir, "watchfaces")` 与 `File(filesDir, "previews")`。

主用户下通常为：

- `/data/user/0/dev.xiaom.watchfaceinstaller/files/watchfaces/`
- `/data/user/0/dev.xiaom.watchfaceinstaller/files/previews/`

`/data/data/dev.xiaom.watchfaceinstaller/files/` 是常见兼容别名；工作资料和其他 Android 用户的前缀不同，应用按系统返回目录操作，不硬编码 `/data/data`。

本次 BIN 位于 `watchfaces/imported/<原输入摘要>.bin`，处理后覆写同一槽位；修改版另留 `watchfaces/edited/<新摘要>.bin`。预览按内容摘要去重。清理覆盖目录内旧版遗留文件。跨引擎目录锁协调写入和清理；编辑器持有内存快照，队列在独立受租约保护的临时目录中安装。

## 验证

- 最终产品提交：`ed5715c4b7ffd245a21acb1daf7952b257090c7e`；[Actions 43923588729](https://github.com/dian-sen/XiaomiWatchfaceInstaller/actions/runs/43923588729) 的 verify / release 均 success。
- 云端34 个静态分析目标、160 项 Flutter 测试、8 项 ADB Python 测试及3 项 APK ABI Python 测试通过。
- 原生窄编译及72 项 JVM 回归、真实 AIDL 生成、Debug APK、app / XMS 单元测试与 Lint 均通过。
- Release APK 编译、签名、版本与实际 ABI 校验、自动发布均通过。产物：[build-25-ed5715c](https://github.com/dian-sen/XiaomiWatchfaceInstaller/releases/tag/build-25-ed5715c)。
- 下载后独立验收：58,007,190 字节，包名 `dev.xiaom.watchfaceinstaller`，版本 `0.8.1+14`，minSdk26 / targetSdk36，非 debuggable，禁止系统备份；ZIP CRC / ELF 检查通过，仅 ARM64 共13 个原生库。
- APK SHA-256：`db5f2dcc2c0ae3f4eac9baa3bca674d4dfca518ba3ff954cc9ebc7ccac2da220`，与发布资产 digest 一致。Google apksig9.0.1 独立 v2 验签通过；证书 `d96417b1091218fcb8b5b946ea988597ea3853fb2428ab92f43c5698fe710ff9`，与旧版相同。
- `actionlint`、Bash 语法与 `git diff --check` 通过。
- 在线只读核验确认 GitHub 源能返回完整包、增量包、长度和 digest；另下载139,604 字节完整包样本，SHA-256 与源一致。
- 未读取用户实际私有文件、执行清理、安装或启动真机应用、连接穿戴设备或刷写表盘/固件；以上自动验证不能替代真机兼容性测试。

版本号、Android versionCode、工作流产物校验、BUILD-INFO 和发布说明已同步为 `0.8.1+14`。沿用此前发布签名身份。
