# 光影盒子 TV

面向电视遥控器操作的 Android 直播播放器，最低支持 Android 4.4（API 19）。

## 功能

- 高清、4K、乘风三类频道源
- 同名频道备用线路自动切换
- HLS、HTTP、HTTPS、RTMP 播放
- 播放网络速度显示
- 电视遥控器焦点与按键动效
- 深色渐变界面、启动过渡、加载和错误状态

## 构建

使用 Android Studio 打开项目，选择 `legacytv` 模块运行，或执行：

```powershell
.\gradlew.bat :legacytv:assembleDebug
```

生成文件：

```text
legacytv/build/outputs/apk/debug/legacytv-debug.apk
```

当前版本：`0.9.0-tv19-polished`，包名：`com.example.streambox.tv`。

请仅使用你有权访问和播放的频道源；频道可用性、清晰度和授权范围由源提供方决定。
