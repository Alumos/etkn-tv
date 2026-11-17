# ETKN TV 0.1.129：合集订阅状态缎带

日期：2026-11-17。

## 变更

合集卡片读取服务端 SubscriptionStatus，显示“待订阅 / 已订阅 / 下载中”缎带，并随卡片尺寸缩放。配套 ETKvNext cdc8b986 已热更新，为 Web/TV 合集提供未入库影片占位、图片与详情。

## 发布与验证

- 源码提交 e977fba09879ddcbc688c45fb718c85ebddaae64；main 和 v0.1.129 已推送。
- 版本 0.1.129，versionCode 130，minSdk 24、targetSdk 35。
- assembleRelease、lintRelease、testReleaseUnitTest 通过，Release 单元测试报告 126 项、0 失败/错误。
- 发行证书 SHA-256：e02d42443875c3631be48199e0df419b0570fcc421be7ff2b620f4c643e67bd7；沿用原私钥。
- 20 个原生库与 0.1.128 逐字节一致。
- APK SHA-256：31497afe5f741baa77964d52f1f022e2dd3d6607648d9e7534a2e3cc685082cb。
- https://github.com/hbq0405/etkn-tv/releases/tag/v0.1.129 已正式发布并成为 latest；发布时间 2026-11-17T08:47:02Z。
- 四个发行资产（APK、update.json、SHA256SUMS.txt、RELEASE_NOTES.md）在草稿时核对正文/大小/哈希，发布后经匿名公开下载再次比对通过。
- 发行通知工作流 44049682816 成功，真实数字回执仅 1 次，未重跑通知。
- 正式资产位于 D:/ETKvNext-agent-tmp/20261117-collection-placeholders/tv-release/；临时下载缓存已清理，旧 outputs 不提交。

## 验收边界

当前 adb devices 无连接设备，未执行真机安装、缎带外观或遥控操作验收。配套服务端仓储对照、DTO、健康和文件一致性通过；浏览器正常登录后的交互未覆盖。
