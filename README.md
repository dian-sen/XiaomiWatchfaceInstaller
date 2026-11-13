# XiaomiWatchfaceInstaller

Android 工具：读取小米运动健康本地表盘缓存，校验 `resource.bin`，并通过 Android 文件 URI 交给 Gadgetbridge 等现有安装器。首个目标机型为 **Xiaomi Band 9 Pro**。

> 当前版本是安全的提取与外部安装交接 MVP。小米手环 9 Pro 的自有 BLE/AuthKey 直装适配器尚未宣称完成，避免把未经真机验证的协议代码误用于设备。

## 已实现

- Shizuku UserService 只读扫描：
  - `/storage/emulated/0/Android/data/com.mi.health/files/WatchFace/`
  - 旧版 `/storage/emulated/0/Android/data/com.xiaomi.hm.health/files/watch_skin_local/`
- 递归枚举 `.bin`，限制深度、数量和单文件大小。
- 识别 Xiaomi BIN magic、ID、名称、SHA-256；不把扩展名直接当作有效格式。
- 应用私有目录保存工作副本。
- FileProvider + `application/octet-stream` 外部安装交接接口。
- GitHub Actions：标签或手动触发构建 Release APK 和 SHA-256。

## 重要限制

Android 11+ 标准 SAF 不保证访问 `Android/data`。Shizuku 以 ADB 启动时通常是 shell UID，不等于 root，不能因此读取其他应用私有数据库。AuthKey 不会自动从未知数据库提取，也不会写入日志。

`0x28` 是已核实二进制格式的 ID 起点，但 9 字节规则不能套用所有型号；Band 7 等设备可能使用 ZIP/Zepp OS 包。不要无条件修改 ID，也不要把它当作签名绕过。

## 本地构建

需要 JDK 17、Android SDK 35 和 Gradle 8.9：

```bash
gradle assembleDebug
# 或
gradle lintDebug
```

当前工作区未内置 Gradle Wrapper；CI 使用官方 Gradle setup action。构建前应在开发环境运行 lint 和单元测试。

## 发布

推送 `v*` 标签，或在 Actions 手动运行 `Release APK`。工作流会构建未签名 release APK、生成 SHA-256 并发布 GitHub Release。生产分发应另行配置 Android 签名密钥，不要把 keystore 或密码提交到仓库。

## 许可证与来源

协议实现应参考 Gadgetbridge，并遵守其 AGPL-3.0-or-later。表盘资源的版权和小米服务条款不因本项目而改变。研究依据包括 Xiaomi-BiaoPan、Mi8WfBinTool、Gadgetbridge、AstroBox 和 BandBBS 公开资料。
