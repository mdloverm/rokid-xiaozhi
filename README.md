# 小智 AI 眼镜端应用

基于 Rokid CXR-S SDK 开发的小智 AI 语音对话眼镜应用，采用赛博朋克风格的绿色矩阵 UI。

## 项目结构

```
xiaozhi-glasses/
├── app/
│   ├── src/main/
│   │   ├── java/com/rokid/xiaozhi/
│   │   │   ├── MainActivity.kt              # 主界面（WebView 容器）
│   │   │   ├── audio/
│   │   │   │   ├── AudioService.kt          # 音频服务（录音/播放）
│   │   │   │   └── OpusDecoder.kt           # OPUS 音频解码
│   │   │   ├── core/
│   │   │   │   └── DeviceManager.kt         # 设备管理
│   │   │   ├── network/
│   │   │   │   └── XiaozhiWebSocketClient.kt    # WebSocket 客户端
│   │   │   ├── util/
│   │   │   │   └── WifiManager.kt           # WiFi 管理
│   │   │   └── views/
│   │   │       ├── AudioVisView.kt          # 音频可视化
│   │   │       ├── ScanlineOverlay.kt       # 扫描线覆盖层
│   │   │       └── VadWaveView.kt           # VAD 波形视图
│   │   ├── assets/
│   │   │   └── chat_ui.html                 # Web UI（矩阵风格）
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   └── activity_main.xml        # WebView 布局
│   │   │   ├── values/
│   │   │   │   ├── colors.xml               # 绿色矩阵颜色定义
│   │   │   │   ├── strings.xml              # 字符串资源
│   │   │   │   └── themes.xml               # 主题样式
│   │   │   ├── drawable/                    # 图标资源
│   │   │   └── mipmap-*/                    # 应用图标
│   │   └── AndroidManifest.xml              # 应用配置和权限
│   ├── libs/
│   │   └── cxr-service-bridge-1.0.aar       # Rokid CXR-S SDK
│   └── build.gradle.kts                     # 应用构建配置
├── gradle/wrapper/
│   └── gradle-wrapper.properties            # Gradle Wrapper 配置
├── build.gradle.kts                         # 项目级构建配置
├── settings.gradle.kts                      # 项目设置
├── gradle.properties                        # Gradle 属性
└── gradlew.bat                              # Windows 构建脚本
```

## 开启后的使用说明

### 1. 首次启动

1. 打开应用，自动请求录音和网络权限
2. 应用会自动尝试连接 WiFi
3. 如未自动连接，会弹出 WiFi 设置界面

### 2. 对话流程

```
WiFi 连接成功 → 连接服务器 → 进入监听状态
     ↓
用户说话 → 自动识别 → AI 回复播放
     ↓
回复完成 → 自动进入下一轮监听
```

### 3. 界面说明

- **顶部标题**：J.A.R.V.I.S
- **状态栏**：显示当前状态（Initializing... / WiFi Ready / Connecting... 等）
- **状态指示器**：绿色脉冲圆点，闪烁表示活跃
- **VAD 波形**：说话时显示动态波形
- **聊天区域**：显示对话历史

### 4. 构建和安装

```bash
# 构建 Debug APK
./gradlew assembleDebug

# 安装到设备
adb install app/build/outputs/apk/debug/app-debug.apk
```
