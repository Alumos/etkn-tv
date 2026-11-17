# 快进与快退预览缩略图本地验证

日期：2026-11-17。对应服务端报告：`D:/work/ETKvNext/docs/reports/2026-11-17-video-preview-thumbnails.md`。

- 遥控器方向键、媒体快进/快退键和完整控制栏进度条操作期间显示最近时间点缩略图；原步长、250ms 合并跳转和控制栏唤出方式保留。
- 首次操作才请求当前媒体版本清单；75ms 节流，最多一张图片在途并追赶最新目标。结束操作、切版本/集、退出和账号变化隐藏并拒绝迟到响应，图片沿用现有缓存。
- 服务端必须更新预览 API 并已生成该视频 BIF；无预览时正常快进/快退。
- 正式签名 assembleRelease、lintRelease 和 128 项单元测试通过；无 Lint Error，111 个 Warning；新增最近帧选择测试覆盖快进/快退及两端边界。
- APK：`D:/work/ETKN-TV/outputs/seek-previews-20261117/etkn-tv-0.1.130-preview-validation.apk`。SHA-256：3c7276a0c593c7eb28556bb69b1f5204e098c1b422400dc80c4adc8745817f1b。
- 签名证书 SHA-256：e02d42443875c3631be48199e0df419b0570fcc421be7ff2b620f4c643e67bd7。
- 候选包仍为 0.1.130/code 131，未递增正式发行版本、未安装、未发布、未推送。电视真机快进退显示效果、真实网络时延及第三方播放器未验收。
