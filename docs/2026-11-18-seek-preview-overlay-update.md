# 预览缩略图候选覆盖安装

2026-11-18 按用户要求，将提交 `049dab6` 的正式签名候选覆盖安装到已连接的 L055FCN / Android 9 电视。

- `adb install -r` 返回 Success；仍为 `xyz.etkn.tv`、0.1.130/code 131。
- 安装后回读 APK 与候选 SHA-256 一致：`3c7276a0c593c7eb28556bb69b1f5204e098c1b422400dc80c4adc8745817f1b`。
- 安装前后证书相同：`e02d42443875c3631be48199e0df419b0570fcc421be7ff2b620f4c643e67bd7`。
- 辅助服务列表保持 `xyz.etkn.tv/.BootStartupService`、总开关保持 1，StartupReceiver 启用保留；实际服务 `hasBound=true`。首次安装时间和数据 inode 保留，没有卸载/清数据。
- Leanback 入口启动成功。本轮未重启电视；未将启动成功或服务绑定当作快进/快退缩略图视觉验收。安装后设备进入播放页，未继续遥控测试。
- ETKN 生产已同步热更新并应用 0201；已有 128 帧预览的清单、JPEG、BIF Range/HEAD 接口只读验收通过。

详细部署、回滚和机器证据保存在 `D:/work/ETKvNext/docs/reports/2026-11-18-preview-thumbnails-hot-update.md` 及同名 verification/manifest JSON。本轮未推送、未创建发行版，候选 APK 继续保留在 `outputs/seek-previews-20261117/`。
