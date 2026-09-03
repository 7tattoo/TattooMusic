# 刺青音乐 (Tattoo Music)

一款基于开源接口 [kuwoMusicApi](https://github.com/qyhqiu/kuwoMusicApi) 的 Android 在线音乐播放器。
包名 `com.spotify.music`，仅构建 arm64-v8a (V8A) 架构。

## 功能

- 在线音乐：每日推荐、猜你喜欢、推荐歌单、排行榜、歌手/搜索
- 我的：自建歌单 / 收藏歌单 / 最近播放
- 本地音乐：支持扫描目录、mp3/aac/flac 等主流格式，读取内嵌 LRC 歌词并滚动显示
- 播放页：竖屏滚动歌词 + 评论/下载入口；横屏默认封面、点击切换滚动歌词，自适应方屏/横屏车机
- 设置：睡眠定时器、本地目录过滤、独占 USB 输出 (USB DAC)、vivo JoviIncCar 车载歌词、关于软件
- 车载：vivo 智能车载主页滚动歌词（metadata + extras 双通道）

## 关键配置

| 项目 | 值 |
|------|----|
| 应用名 | 刺青音乐 / Tattoo Music |
| 包名 | com.spotify.music |
| minSdk / targetSdk | 26 / 35 |
| ABI | arm64-v8a |

## 构建

```bash
# 需要 JDK 17 + Android SDK (compileSdk 35)
./gradlew :app:assembleDebug      # 调试包
./gradlew :app:assembleRelease    # 发布包(已签名)
```

## 签名

仓库内置开发签名 `keystore/release.keystore`，用于 CI 自动产出可安装 APK：
- storeFile: `keystore/release.keystore`
- storePassword / keyPassword: `tattoo2024`
- keyAlias: `tattoo`

如需上架应用市场，请自行替换为正式签名，并在 `keystore.properties` 中覆盖默认值
（`storeFile`/`storePassword`/`keyAlias`/`keyPassword`，此文件不提交）。

## CI

`.github/workflows/build.yml` 在 push / tag 时自动构建，上传 APK 产物；
打标签 (`git tag vX.Y.Z`) 时自动发布 GitHub Release 附件。

## 致谢

- [kuwoMusicApi](https://github.com/qyhqiu/kuwoMusicApi) — 音乐数据接口