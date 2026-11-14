# BLE 协议代码来源及许可

`app/src/main/java/dev/xiaom/watchfaceinstaller/ble/` 的 XiaomiWire、XiaomiSessionCrypto 和 XiaomiBleV1LabSession 为参考/改编 Gadgetbridge 的协议研究代码。固定上游 commit：`221ae4eb08423e113968145f7b93ec8303280295`。逐文件链接及功能边界见 [BLE-SOURCES.md](BLE-SOURCES.md)。AndroidBleTransport 和 BandInstallActivity 也已标注相同 SPDX；这里不对整项目的最终许可证作无依据判断。

上游版权声明包括：

- XiaomiCharacteristicV1 / XiaomiDataUploadService：Copyright (C) 2023–2024 Andreas Shimokawa, José Rebelo。
- XiaomiAuthService：Copyright (C) 2023–2024 Andreas Shimokawa, José Rebelo, Yoran Vulker。
- XiaomiWatchfaceService / XiaomiUuids：Copyright (C) 2023–2024 José Rebelo。
- MiBand9ProCoordinator（仅作型号依据）：Copyright (C) 2023 Yoran Vulker。

协议改编文件受 **GNU Affero General Public License v3 or later (AGPL-3.0-or-later)** 约束，不应移除原版权/许可声明或将其作为无许可证片段分发。原通知保留在 `ble/ProtocolLicense.txt`；完整上游许可证文本保存在 `ble/AGPL-3.0.txt`。

本地修改包括：Kotlin 有界 wire codec；显式 proto2 零值；严格 HMAC/CCM 验证；不打印/持久化 AuthKey；分包边界检查；不可用固件的构造期 fail-closed gate；仅用户主动触发的 GATT 服务诊断；私有表盘副本编辑与结果返回。没有声称由 Gadgetbridge 官方提供、认可或保证互通。

如分发包含这些改编代码的 APK，发布方需要核对整个组合的许可证兼容性，并履行适用的 AGPL 对应源码/通知等义务。仅写一份 notice 不是对全部分发义务已完成的证明；若项目不接受 AGPL，不应发布包含该改编协议代码的构建。本次不替主 Agent 修改仓库根许可证、README、构建或发布文件。

BC 采用外部依赖 `org.bouncycastle:bcprov-jdk18on:1.80`（测试版本）；其许可证独立于上述 AGPL 通知，分发时须保留依赖自带的许可证材料。不需要在 Android 系统中安装或替换安全 Provider。
