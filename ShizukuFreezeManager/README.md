# Shizuku Freeze Manager

独立 Android 项目目录，Kotlin + Jetpack Compose + Shizuku。不会改动仓库根目录的 PureGallery。

## 构建
使用 Android Studio 打开本目录，或由仓库根目录 GitHub Actions 工作流构建 `app-debug.apk`。

## 安全原则
- 所有应用实例使用 packageName + Android userId 作为唯一键。
- 只有明确识别到用户 ID 后才允许冻结、解冻或启动。
- 启动前必须解析指定用户下的 launcher Activity；解析失败时拒绝启动。
- 一键冻结跳过自身、系统应用和白名单。
- 仅调用 Shizuku shell 的 Package Manager / Activity Manager 命令，不使用 Root、设备管理员或无障碍服务。

当前为首个工程骨架，ColorOS 多开用户枚举与启动解析仍需在 realme Neo8 实机验证后才能作为可靠功能交付。
