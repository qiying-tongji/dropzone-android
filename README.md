# DropZone Android

原生 Android 本地个人记忆库。当前 APK 已在 Android 35 Pixel 模拟器完成编译、安装和主要页面检查。

## 环境

- JDK 17 或 Android Studio 自带 JBR
- Android SDK Platform 35、Build Tools 35.0.0
- Gradle 8.13（JDK 17）
- 模拟器或 Android 10+ 真机

在项目根目录创建 `local.properties`，设置 `sdk.dir` 为 Android SDK 路径。
执行 `gradle :app:assembleDebug`，再用 `adb install -r app/build/outputs/apk/debug/app-debug.apk` 安装。

## 当前实现

本地 SQLite 数据、照片长期 URI 引用、系统文档选择器多选、链接及文字分享、中文 ML Kit OCR、ML Kit 本地图像标签、字段关键词匹配与依据、事件多对多附件、独立日记、周年回顾隐藏、文字导出、全相册枚举及排除项、关闭识别与清空数据。图像标签在界面中本地翻译为中文并保留置信度；英文原始标签作为隐藏索引保留，因此中英文均可检索。

界面参考 SRS UI Mockups 重构：首页提供搜索与四个低门槛入口，未整理和合集承担浏览；搜索页提供类型筛选、示例查询和匹配依据；详情页展示原始内容、标签、保存文字、OCR 和来源入口；导入页单独呈现处理状态。整体使用参考图的深蓝、亮蓝、浅蓝灰配色和统一线性矢量图标。

所有二级页面的左上角返回按钮使用 48dp 点击区域，并避开系统状态栏；Android 系统返回键采用相同的页面返回逻辑。

## 尚待完成与验证

- 当前是 SQLiteOpenHelper 原型，尚需迁移为设计稿中的 Room + FTS；搜索已加入昨天、上周、本周、上个月的日期规则，尚不支持中文分词与加权排序。
- OCR 与通用图像标签已接入 WorkManager 持久任务与重试，尚未验证进程重启恢复；未持久保存文字块坐标，通用标签模型只输出英文类别。
- 全相册当前每次手动全扫，尚未实现增量 generation、ContentObserver 与完整的失效类型区分。
- 图片分享目前提示从相册重选，尚未尝试核实持久授权与同源身份。
- 日记当前独立保存，尚未实现条目、事件、日期的三类挂载。
- 自定义领域视觉模型、相关资料建议、提醒、稍后处理、图片 ZIP/HTML 导出尚未实现。
- UI 为原生 View 实现，尚未迁移 Compose；模拟器中保留一条明确标注的演示链接。
- 未验证第三方 SDK 的实际联网诊断行为及设备迁移备份排除行为。
- 所有性能、质量与隐私指标均待真机验收，不应视为已通过。

## 验收重点

重启后引用、拒绝权限、部分授权、关闭全库、删除与 OCR 并发、原图暂时离线、离线 OCR、事件附件删除、跨年和闰日、导出失败。禁止通过 UI 外观替代这些行为验证。

## PxHere 测试照片

`test-images/pxhere` 保存了 50 张来自 `nyuuzyou/pxhere` 的 CC0 测试照片（约 146 MB）。`manifest.csv` 记录每张照片的原始下载地址、数据集行号和测试类型；同一批照片也已写入模拟器的 `Pictures/DropZoneTest` 相册。
