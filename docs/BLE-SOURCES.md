# 小米 9 Pro 传输核查与本实现边界

## 固定上游，不把默认值当硬件断言

核查官方仓库 `https://codeberg.org/Freeyourgadget/Gadgetbridge.git` 的 `git ls-remote origin HEAD refs/heads/master`，两者均返回 **221ae4eb08423e113968145f7b93ec8303280295**；随后 `git fetch --depth=1 origin master` 成功，研究工作树 HEAD 相同。这是本次查询到的最新 master，不是“所有未来固件”的保证。以下源码链接全部固定此 commit。

官方文档：[Xiaomi protobuf watches](https://gadgetbridge.org/basics/topics/xiaomi-protobuf/) 的连接故障段明确说明：部分小米设备依固件版本支持 BLE 或 Classic，Gadgetbridge 尚不能自动确定连接类型。该说明不列出每个 9 Pro 固件对应的传输，不等于已经证明所有 9 Pro 都支持两者。

- [MiBand9ProCoordinator](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/221ae4eb08423e113968145f7b93ec8303280295/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/xiaomi/watches/MiBand9ProCoordinator.java)：9 Pro 名称正则、`isExperimental() = true`、默认 `BT_CLASSIC`。
- [XiaomiSupport](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/221ae4eb08423e113968145f7b93ec8303280295/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/XiaomiSupport.java)：`createConnectionSpecificSupport()` 只在 coordinator 为 BOTH 时读取强制连接偏好；BT_CLASSIC 选 XiaomiSppSupport。不能把官网通用“强制 Classic”说明误读为本 App 已有固件探测。
- [XiaomiSppSupport](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/221ae4eb08423e113968145f7b93ec8303280295/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/XiaomiSppSupport.java)：RFCOMM/SPP 不是 GATT 换一个 UUID。包含版本询问、V1/V2 切换、会话初始化、认证和数据通道。上游存在版本超时回退与 `>=2` 选择，本 App **没有照搬这些宽松回退**。
- [XiaomiSppProtocolV1](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/221ae4eb08423e113968145f7b93ec8303280295/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/XiaomiSppProtocolV1.java) / [XiaomiSppProtocolV2](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/221ae4eb08423e113968145f7b93ec8303280295/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/XiaomiSppProtocolV2.java)：可供后续移植核查；本工程未实现 Classic socket、版本握手或 V2 发送窗口。

**结论：已修正“9 Pro 不是 BLE”的错误绝对化表述。但当前交付不是 9 Pro 直装器，BLE 与 Classic 均不提供上传入口。** 没有与用户具体固件对应的、经过协议握手及设备结果验证的 profile 时 fail closed。广播名、FE95、BIN magic、ID 长度都不能代替身份/型号/固件兼容性证明。

## 离线协议参考

以下是保留原有工作成果的研究实现，不表示已完成设备互通。

- [XiaomiUuids](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/221ae4eb08423e113968145f7b93ec8303280295/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/XiaomiUuids.java)：FE95 下 BLE v1 0051/0052/0053/0055，BLE v2 005e/005f；共享服务不能识别型号。
- [XiaomiCharacteristicV1](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/221ae4eb08423e113968145f7b93ec8303280295/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/XiaomiCharacteristicV1.java)：小端分片号、ACK/NACK、单包 6 字节预算、加密计数器。注意 `isEncrypted` 是**特征 profile**，不是“握手已完成”；明文特征 profile 总是分片，加密特征在握手前仍可以发送明文单包。`XiaomiFrames.encode` 已区分这两个参数。
- [XiaomiAuthService](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/221ae4eb08423e113968145f7b93ec8303280295/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/XiaomiAuthService.java)：nonce、HMAC-SHA256 派生、miwear-auth、AES-CCM。这里不复刻上游秘密调试日志，验证设备 HMAC 和 CCM tag；不会提取 AuthKey。
- [XiaomiDataUploadService](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/221ae4eb08423e113968145f7b93ec8303280295/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/services/XiaomiDataUploadService.java)：type 16、MD5、size、resume、CRC32 及分包 envelope。
- [XiaomiWatchfaceService](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/221ae4eb08423e113968145f7b93ec8303280295/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/services/XiaomiWatchfaceService.java)：watchface prepare/list 与上传回调。写入完成或传输 ACK 不等于设备已经安装。
- [xiaomi.proto](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/221ae4eb08423e113968145f7b93ec8303280295/GBProtobufGenerator/src/main/proto/xiaomi.proto)、[wear_account.proto](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/221ae4eb08423e113968145f7b93ec8303280295/GBProtobufGenerator/src/main/proto/xiaomi/wear_account.proto)、[wear_watch_face.proto](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/221ae4eb08423e113968145f7b93ec8303280295/GBProtobufGenerator/src/main/proto/xiaomi/wear_watch_face.proto)、[wear_mass.proto](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/221ae4eb08423e113968145f7b93ec8303280295/GBProtobufGenerator/src/main/proto/xiaomi/wear_mass.proto)：proto2 的显式零字段及嵌套 field 编号。

`XiaomiBleV1LabSession` 保留，但构造函数第一步就抛出阻塞错误，在启动通知 reader 或发包之前 fail closed。不能直接删除 gate 宣称可用：还缺完整传输选择、固件白名单/能力确认、状态机重放、断线/重传/乱序测试、有效认证材料交互和最终设备端安装结果验证。其强制 DeviceConfirm 布尔值、拒绝非零 resume 等规则比上游严格，可能拒绝合法固件；不是兼容性实现。

## 当前可交付的内部 Activity 合约

`BandInstallActivity` 必须在 Manifest 中 `android:exported="false"`，无 intent-filter。主界面用 Activity Result launcher 启动，传 `EXTRA_PRIVATE_PATH`（`private_path`）为本 App filesDir/cacheDir 内真实工作文件的绝对路径。

- 进入时做有界私有快照，保存到 `filesDir/watchfaces/band-install/`。
- `WatchfaceIdEditor.createCopy(filesDir, source, newId)` 只接受已识别 5AA53412 头、NUL 终止的 9/12 位 ASCII 数字 ID；新旧必须等长且不同。只改变 ID 字节，不覆盖源文件、不声称修复签名/校验。
- 改 ID 副本在 `filesDir/watchfaces/id-copies/`，与现有 `<files-path name="watchfaces" path="watchfaces/"/>` 兼容，不需要扩大 FileProvider 暴露目录。
- 点击“返回当前副本，交主界面导出”才 `setResult(RESULT_OK, ...)`：`Intent.data` 为 `${applicationId}.files` FileProvider URI，带 ClipData/read grant；额外返回 `EXTRA_PRIVATE_PATH`、`EXTRA_RESULT_ID`、`EXTRA_RESULT_SHA256`、`EXTRA_RESULT_INSTALLED=false`。
- `RESULT_OK` **仅表示选中了本地副本**。主界面应重新检查本 App 内路径、文件及 hash 后交给已有导出流程，不能显示“已安装”。取消/返回不代表选中副本。这里没有修改 MainActivity 或导出文件。
- 私有副本会保留供主界面读取；后续清理应在导出完成/用户删除之后，不能在 onStop/onDestroy 清掉返回文件。

## 主 Agent 必须补的集成（本次按分工未修改）

依赖：`implementation("org.bouncycastle:bcprov-jdk18on:1.80")`。这是本次以 Debian BC 1.80 jar 编译和测试的版本，不是“最新 BC”声明。只用 lightweight AES/CCM API，不注册/替换系统 Provider，不需要 bcpkix。仓库当前没有该 dependency；外部 init script 注入不等于正式发布已配置。

Manifest（BLE 诊断可选，不需要启用扫描之外的设备能力）：

```xml
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<!-- inside application -->
<activity android:name=".BandInstallActivity" android:exported="false" />
```

API 31+ 运行时请求 SCAN/CONNECT；26–30 请求 FINE_LOCATION。用户手动开启蓝牙，旧 Android 可能还要求开启位置服务。`neverForLocation` 可导致某些广播被系统过滤；零结果不能证明手环不存在或不支持 BLE。不需要 ADVERTISE、后台定位、后台服务、蓝牙开关控制或新增 Root 权限。

## 验证边界

`WatchfaceIdEditorTest`：9/12 位逐字节保真、源文件不变、输出目录、错误 ID/长度、未知/截断/NUL 错误、目录越界、符号链接、失败副本清理。

`XiaomiProtocolVectorTest`：proto2 显式零与 install/upload/nonce 字节向量、Python 标准库独立生成的 HMAC 派生与 MD5/CRC32 envelope 向量、单包/分片/nonce 小端、明文特征 profile、malformed/duplicate field 拒绝、CCM roundtrip/tag tamper、HMAC 失败、密钥字符清理和 fail-closed gate。

这些是离线单元测试；CCM roundtrip 不是独立的 CCM 已知答案向量。没有调用手机蓝牙、配对、连接、安装；没有证明真机 BLE 诊断、Activity 返回 UI 或设备表盘安装成功。实际测试及 Android 编译结果见本次交接报告。
