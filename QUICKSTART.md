# 小智 AI 眼镜端项目

## 快速开始

### 1. 配置服务器地址

打开 [`app/src/main/java/com/rokid/xiaozhi/MainActivity.kt`](app/src/main/java/com/rokid/xiaozhi/MainActivity.kt)

修改以下配置：

```kotlin
companion object {
    // 替换为你的服务器地址
    private const val SERVER_URL = "ws://192.168.1.100:8000/ws"
    private const val TOKEN = "your-token-here"
}
```

### 2. 构建并安装

```bash
# 进入项目目录
cd cxrs/xiaozhi-glasses

# 构建 Debug 版本
./gradlew assembleDebug

# 安装到眼镜
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 3. 使用说明

1. 确保眼镜已连接到 Wi-Fi
2. 打开"小智 AI"应用
3. 点击"开始对话"按钮
4. 按住说话，松开发送

## 后端服务搭建

可以使用小智 AI 服务端：

```bash
git clone https://github.com/xinnan-tech/xiaozhi-esp32-server.git
cd xiaozhi-esp32-server
pip install -r requirements.txt
python app.py
```

详细配置参考：[小智服务端文档](https://github.com/xinnan-tech/xiaozhi-esp32-server)

## 注意事项

- 当前版本使用 PCM 音频（16kHz, 16bit）
- OPUS 编解码需要集成 native 库
- 眼镜端屏幕较小（480x640），UI 已做适配

## 后续优化

1. 集成 OPUS 编解码减少带宽
2. 添加回声消除（AEC）
3. 优化 UI 适配眼镜显示
4. 添加离线唤醒功能
