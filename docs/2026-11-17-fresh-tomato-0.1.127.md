# TV 新鲜番茄图标替换与 0.1.127 正式发布

日期：2026-11-17。用户明确授权替换 TV 图标并推送发版；本轮只修改、推送 ETKN-TV，RT 补分继续搁置。

## 实际变更

- 新增 app/src/main/res/drawable-nodpi/fresh_tomato.png，与用户 rt-official-1024-deep.png 原始字节相同，SHA-256 为 019754d6f5b7484ce1fffc0a97417d1f572c90b94ee2402f4dfd9cd8387e70ec。
- TvActivity.tomatoMark 的高评分分支改用该本地资源，卡片与详情页共用。原低于 60% 的 rotten_tomato.png 及分数选择逻辑不变，低分资源 SHA-256 为 9ecad9a70c2f927df89d8926e9c988d4e1b18f36fc432ac408b52ff59a8705b6。
- 版本递增为 0.1.127 / versionCode 128。发行源码提交 46c85b079553e0fbe8e9a7daf1ea3c2332e3dd5f；v0.1.127 为带中文说明的标签，标签对象 e832937da720704fce5ce9ac3fe921bfed3e4e09。
- 推送使用 main 与标签的原子推送。工作区既有 outputs/ 未纳入提交，ETKvNext 未修改、未推送或热更新。

## 本地验证

scripts/Build-Release.ps1 执行 Release 单元测试、Lint 和 assembleRelease 全部成功；27 个套件共 126 项测试，失败、错误、跳过均为 0，Lint 0 errors、109 warnings。

沿用现有正式证书，apksigner 验证通过，证书 SHA-256 为 e02d42443875c3631be48199e0df419b0570fcc421be7ff2b620f4c643e67bd7。aapt2 核对包名 xyz.etkn.tv、版本 0.1.127/code128、minSdk24/targetSdk35 及四 ABI。

安装包资源按实际资源表定位：fresh_tomato 为 res/fC.png，rotten_tomato 为 res/aj.png。新图全部 RGBA 像素与用户图片相同；低分图只有完全透明像素的无效 RGB 被 Android PNG 优化清零，透明度与全部可见像素相同。源文件原始字节未变。与已验证的 0.1.126 APK 比较，20 个原生库、25 个许可文件全部原始字节一致。

APK 大小 70082113 字节；SHA-256 为 3ddb4a7a1826d871bccaad8c59bcb4a92aa96aa28543df3e52ea30a7b4e21a61。正式安装包保留在 app/build/outputs/apk/release/app-release.apk。

## 推送与正式发行

Release：https://github.com/hbq0405/etkn-tv/releases/tag/v0.1.127 ，ID 448137849。2026-11-17 12:36:33 北京时间正式发布，latest=true、draft=false、prerelease=false。

正式公告与 update.json 的 releaseNotes 一致：

- 更新高评分的新鲜番茄图标，与 Web 保持一致。

四个正式附件为 etkn-tv-0.1.127.apk、update.json、SHA256SUMS.txt、RELEASE_NOTES.md。发布前核对草稿源码、附件大小和 GitHub 服务端 SHA-256、公告正文；发布后匿名下载四个附件，原始字节全部一致。latest/download/update.json 同样与本地清单逐字节一致，包名、版本、APK 大小、哈希及下载地址全部匹配。

Telegram 发布通知由既有发行事件工作流自动触发，运行 44018364178 对应上述发行源码，success、run_attempt=1；真实数字送达回执恰好一条，message_id=65299，未重跑通知。

## 验收边界

本轮未安装或操作用户电视，尚未进行 TV 真机视觉与遥控器验收。客户端可通过原有更新入口获取新 APK；构建、包内资源与公开下载核验不代替最终电视观感。补分功能仍未实施。

完整结构化证据见 2026-11-17-fresh-tomato-0.1.127-verification.json。发行后报告单独提交，不移动已发布标签或替换已校验附件。
