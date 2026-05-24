# 小智 AI 眼镜端配置说明

## 1. 后端服务器配置

### 方案 A：使用小智 AI 服务端（推荐）

1. 克隆服务端项目：
```bash
git clone https://github.com/xinnan-tech/xiaozhi-esp32-server.git
cd xiaozhi-esp32-server
```

2. 安装依赖：
```bash
pip install -r requirements.txt
```

3. 配置服务器（修改 `config.yaml`）：
```yaml
server:
  host: 0.0.0.0
  port: 8000

xiaozhi:
  token: your-token-here  # 生成一个 token
```

4. 启动服务器：
```bash
python app.py
```

### 方案 B：使用商业版服务端

参考：[小智 AI 商业版](https://xiaozhi.me/)

## 2. 眼镜端配置

### 修改服务器地址

编辑 `app/src/main/java/com/rokid/xiaozhi/MainActivity.kt`：

```kotlin
companion object {
    // 替换为你的服务器 IP 地址
    private const val SERVER_URL = "ws://192.168.1.100:8000/ws"
    
    // 替换为你的 token
    private const val TOKEN = "your-token-here"
}
```

### 配置说明

- **SERVER_URL**: WebSocket 服务器地址
  - 格式：`ws://<IP 地址>:<端口>/ws`
  - 如果在同一局域网，使用局域网 IP
  - 如果需要外网访问，需要配置端口映射或使用内网穿透

- **TOKEN**: 认证令牌
  - 需要与服务端配置一致
  - 用于设备认证

## 3. 网络配置

### 局域网访问

确保眼镜和服务器在同一局域网：

1. 查看服务器 IP：
   - Windows: `ipconfig`
   - Linux/Mac: `ifconfig`

2. 在眼镜上连接同一 Wi-Fi

3. 测试连接：
   ```bash
   ping <服务器 IP>
   ```

### 外网访问

如果需要眼镜在外网也能连接：

1. **方案 A：端口映射**
   - 在路由器配置端口映射
   - 将服务器的 8000 端口映射到公网

2. **方案 B：内网穿透**
   - 使用 ngrok, frp 等工具
   - 示例（ngrok）：
     ```bash
     ngrok http 8000
     ```
   - 使用生成的公网地址

## 4. 音频配置

当前配置：
- 采样率：16kHz
- 位深度：16bit
- 声道：单声道
- 格式：PCM（未压缩）

### OPUS 编解码（待实现）

要启用 OPUS 编解码，需要：

1. 添加 OPUS native 库
2. 修改 `AudioService.kt` 添加编码/解码
3. 修改 WebSocket 协议使用 OPUS 格式

## 5. 测试步骤

### 5.1 测试服务器

```bash
# 启动服务器
python app.py

# 查看日志
# 应该看到：服务器已启动在 ws://0.0.0.0:8000
```

### 5.2 构建眼镜端应用

```bash
cd cxrs/xiaozhi-glasses

# 构建
./gradlew assembleDebug

# 输出位置
# app/build/outputs/apk/debug/app-debug.apk
```

### 5.3 安装到眼镜

```bash
# 通过 ADB 安装
adb connect <眼镜 IP>
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 5.4 运行测试

1. 在眼镜上打开"小智 AI"应用
2. 点击"开始对话"
3. 状态变为"已连接"表示成功
4. 按住说话，测试语音对话

## 6. 故障排查

### 问题 1：无法连接服务器

**症状**: 状态一直显示"连接中..."

**解决方法**:
1. 检查服务器是否启动
2. 检查 IP 地址是否正确
3. 检查防火墙设置
4. 在眼镜上使用浏览器测试 WebSocket

### 问题 2：没有声音

**症状**: 能对话但听不到声音

**解决方法**:
1. 检查眼镜音量设置
2. 检查音频权限是否授予
3. 查看 Logcat 日志中的音频错误

### 问题 3：音频质量差

**症状**: 声音断续或有杂音

**解决方法**:
1. 检查网络带宽
2. 考虑使用 OPUS 编解码
3. 增加缓冲区大小

## 7. 性能优化

### 网络优化

- 使用 OPUS 编解码减少带宽（约 20kbps）
- 启用 WebSocket 压缩
- 优化重连机制

### 音频优化

- 添加回声消除（AEC）
- 添加噪音抑制（NS）
- 自动增益控制（AGC）

### 电量优化

- 优化屏幕亮度
- 空闲时进入低功耗模式
- 优化网络心跳

## 8. 高级配置

### 自定义唤醒词

需要集成离线唤醒引擎：
- ESP-SR（乐鑫）
- PocketSphinx
- Snowboy

### 多语言支持

修改服务端配置，支持：
- 中文（普通话）
- 英文
- 日文
- 其他语言

### 声纹识别

集成 3D Speaker 或其他声纹识别服务

## 9. 相关资源

- [小智 AI 服务端](https://github.com/xinnan-tech/xiaozhi-esp32-server)
- [小智 AI 眼镜端](https://github.com/78/xiaozhi-esp32)
- [Rokid CXR-S SDK](https://ar.rokid.com/)
- [OPUS 音频编解码](https://opus-codec.org/)
