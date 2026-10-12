# Fuji Import

小米 Pad 6S Pro + FUJIFILM X-T5 的 USB 照片浏览与原文件导入应用。

## 工程入口

- Kotlin + Compose；包名 `com.fujiimport.app`，minSdk 29，compile/targetSdk 37。
- JDK 17/21；SDK `platforms;android-37.0`、Build Tools 36.0.0；依赖版本见 Gradle 配置。
- 源码：`app/src/main/java/com/fujiimport/app/`。界面在 `ui/`，USB 在 `data/mtp/`，存储在 `data/media/`，队列在 `domain/`、`service/`。

## 构建与调试

Codex 检查 SDK/JDK，并按实际 SDK 路径生成本地 `local.properties`。

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug assembleRelease --console=plain
```

APK 位于 `app/build/outputs/apk/{debug,release}/`；正式包为 `app/build/outputs/apk/release/app-release.apk`，使用本地 `signing.properties` 和 `.signing/fuji-import-release.jks` 签名，配置示例见 `signing.properties.example`。密钥及配置已由 Git 忽略，请一并备份，后续更新沿用同一密钥。

Debug 与 Release 签名不同，首次从调试版切换正式版需要卸载调试版，会清除应用内设置与导入记录；已导入到公共目录的照片保留。

实机使用无线 ADB 配对、连接，然后执行：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.fujiimport.app/.MainActivity
```

## 实现要点

- 已实现连接授权、增量扫描、日期网格、存储筛选、多选预览、原文件导入、记录、缓存和脱敏诊断。
- JPEG/支持的 HEIF → `Pictures/FujiImport/`；RAF/不支持的 HEIF → `Download/FujiImport/`。
- 重复判断结合源身份、元数据、成功记录及本地文件；未完成文件使用待发布状态和清理日志。
- RAF 预览使用相机缩略图或同目录 JPEG；原生 `importFile` 取消需等待调用返回。
- 第三方许可原文随包保存在 `app/src/main/assets/licenses/`。

[规格](<docs/Fuji Import — Android App 开发规格说明书.md>) · [验证记录](docs/本地验证记录.md) · [实机清单](docs/实机验证清单.md)
