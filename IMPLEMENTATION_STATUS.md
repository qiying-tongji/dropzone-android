# 实施状态

本文件只记录真实完成情况，不表示全部需求已交付。

## 工具链

- Android Studio 2026.2.2.8：winget 安装成功。
- Microsoft JDK 17.0.21：winget 安装成功。
- Android Emulator：已安装，`-accel-check` 返回 WHPX installed and usable。
- Gradle 8.13：已下载，SHA-256 与官方校验文件一致。
- Android API 35 系统镜像、平台和 Build Tools：安装完成。

## 构建

- 使用 JDK 17 和 Gradle 8.13 编译成功。
- APK 已安装到 DropZone_Pixel Android 35 模拟器。
- 已实际检查首页、搜索、导入与处理、记忆详情页面；未发现 AndroidRuntime 崩溃。
- UI 已依据 SRS UI Mockups 重构并同步到桌面项目。
- 已验证左上角返回：导入页返回首页、导入记录详情返回导入页；系统返回键行为一致。
- 顶栏已加入状态栏安全间距，返回按钮点击区域为 48dp；字符图标已替换为统一矢量图标。
- 已接入 ML Kit 中文 OCR 与本地图像标签识别，标签包含置信度并参与搜索。
- 已下载 50 张 PxHere CC0 测试照片（约 146 MB），同步到桌面项目和模拟器 `Pictures/DropZoneTest`；MediaStore 验证为 50 张。

## 后续完成顺序

1. 将原型数据层对齐 Room/FTS，并评估是否迁移 Compose。
2. 补齐增量扫描、来源身份与授权生命周期、文字块证据、日记挂载。
3. 完成增强项：自定义领域视觉模型、资料建议、提醒、稍后处理、完整事件导出。
4. 执行 OCR/图像标签、权限、离线、并发删除、性能与检索质量验收。

详细实现限制见 README.md。尚未执行的验收不可计为通过。
