# NAS漫画阅读器

一款 Android 上的漫画阅读器，可以把 NAS 当作书库。

手机本地目录、家里的 NAS，放什么读什么。压缩包不用解压，打开就是书。

## 当前进度

项目骨架已就绪（可构建、可安装），阅读器功能开发中。

## 界面

<img src="design/shelf.png" width="300" alt="书架">

## 构建

需要 JDK 17 与 Android SDK。

```bash
echo 'sdk.dir=<你的 Android SDK 路径>' > local.properties
./gradlew :app:assembleDebug
```

## 支持一下

这个软件完全免费，没有广告，也没有任何付费功能。

如果它帮你省了点时间，欢迎请作者喝杯咖啡 ☕

<img src="design/wechat-pay.png" width="240" alt="微信收款码">

微信扫码，随心就好。

赞助不换取任何功能优先权，也不会改变 issue 的处理顺序。

## 许可

[Apache-2.0](LICENSE)
