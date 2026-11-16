# Watchface Studio 0.8.0 重构验收记录

日期：2026-11-16。目标仓库：dian-sen/XiaomiWatchfaceInstaller；原基线 ab72fa0 (0.7.4 / 12)。此记录为本地实现与离线验收，不代表 APK 完整打包或设备兼容性认证。

## 实现范围

- 迁入私有 OronBox 706fe87 的 Flutter 自用核心、Xiaomi/ZeppOS 协议、小米/华米账号与本地安装队列。旧 Kotlin 版本保留在 legacy-android/。
- 首页 /studio 与表盘工作台在未连接设备时可用；安装仍检查设备与协议就绪，沿用用户确认及队列。
- 设备密钥中心 /device-keys：小米账号同步、穿戴日志导入、手动添加入口及 Root n67cn 只读数据库快照。Root 密钥不以明文列表显示，不经 Intent 或文件导出。
- Root/Shizuku 缓存读取共用固定目录与受限操作。Shizuku UserService 接收类型化请求，拒绝任意 shell、输出路径和非 UID0/2000 身份；受限管道、超时、取消、engine detach 和私有副本清理保留。
- ADB 采用电脑端 Python+官方 adb 只读导出 ZIP，再传回应用导入。没有应用内无线 ADB 配对；不能绕过 OEM 对目录的限制。
- BIN/预览重命名导出、ZIP 导入导出、9/12 位 ID 副本编辑保留。ZIP 在内存校验路径、条目、实际输出、CRC、SHA-256 和预算；BIN 原始 ID 继续用于安装策略。
- ColorOS 17 风格主题、明暗/OLED模式、大标题和分组卡片；新应用品牌与图标为 Watchface Studio，保留 OronBox/OrPudding 来源文字和许可。
- 包名仍为 dev.xiaom.watchfaceinstaller；版本 0.8.0+13；namespace org.zxor.oronbox；minSdk26。发布只使用既有 release 证书配置，缺失时拒绝构建，不回退 debug key。

## 实际验收

| 项目 | 结果与证据 |
| --- | --- |
| Flutter / Dart | Flutter3.47.9，框架c197ca5199e9ee5661e865596c9c07294dfab51c，Dart3.13.7 |
| 依赖锁 | flutter pub get --enforce-lockfile 成功；使用固定 pubspec.lock |
| 国际化 | 5份ARB合并43项新键，flutter gen-l10n通过 |
| 定向静态分析 | CI的20组分析目标通过，无诊断；最后的设置/关于/密钥页面复检通过 |
| 最终统一功能回归 | bash tool/ci_verify.sh退出0，143项Flutter测试通过 |
| ADB 导出器 | 8项Python离线测试通过；Python实际导出包→Dart实际导入互通通过，不连接真实手机 |
| 原生缓存 | 仓库内run_jvm_tests.sh实际执行，72项JUnit测试通过 |
| 原生API编译 | Root/Shizuku缓存实现通过Android API编译；AIDL使用签名桩，此检查不代替Gradle AIDL生成 |
| 密钥桥编译 | DeviceKeyBridge/RootAuthKeySource/AuthKeyRecord以实际Flutter embedding和Android API编译成功 |
| 密钥模型 | 6项JUnit合成数据测试通过，含密钥清零、字段验证与固定shell语法 |
| CI脚本 | actionlint、bash语法、Python语法检查通过；签名缺失时在解码/编译前拒绝发布 |
| 源码差异 | 暂存差异git diff --check通过；固定XMS gitlink6483f939785e9c1dd011465d573931f669a6adab |

本机是 ARM64 Alpine PRoot。Flutter tester 在 gcompat 下曾发生 Widget 堆栈溢出，改用隔离的 Debian glibc 后同一组测试通过。该宿主适配不进入产品。大型目录原生测试在 PRoot 下使用测试专用 WATCHFACE_TEST_TIMEOUT_MS=60000，产品超时未放宽。

## 独立审查与修复

审查指出安全存储的读取失败回退、重置后旧数据恢复及迁移中断残留问题。已修复：读取异常直接中止；已有权威vault时不降级覆盖；重置提交并验证持久化空vault；成功读取后重试旧来源清理。新增相应失败/重启/中断回归通过。记住的用户名密码纳入系统安全存储；只读device.info不返回密钥明文，设备信息页掩码显示。

## 尚需实际构建或设备验证

- 此时尚未公开推送迁入源码，未执行GitHub完整Android构建、真实AIDL生成、D8/R8/资源打包及最终APK验签。
- 尚未验证旧版本同证书覆盖升级、真机启动、小米/华米在线登录、Root提示、Shizuku授权/服务存活、真实官方缓存访问和手表安装。
- Root密钥读取仅固定支持n67cn，不能宣称适配所有型号。
- ADB仅为电脑导出路径；应用内无线调试配对不在本版本实现。
- 保留协议和功能不等于所有型号已适配；ID修改不修复签名/校验或机型兼容性。

## 公开交付边界

将迁入的核心源码推送公开仓库意味着公开私有OronBox对应源码快照。保留来源/附加条款，不包含仓库Secrets、设备真实密钥或签名材料。现有Actions的main推送会在验证成功后用既有Secrets生成签名ARM64预发布Release；发布失败不得宣称已交付APK。公开推送需用户明确确认此范围。
