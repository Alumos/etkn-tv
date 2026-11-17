# TV 详情页 Logo 加载与安全边距

日期：2026-11-17。版本保持 0.1.127／128，本轮没有发布。

## 修改

`DetailActivity.updateDetailLogo()` 在有效 Logo 加载期间先隐藏片名，成功后显示 Logo；无 Logo、关闭策略或加载失败时显示文字。标题区域尺寸保持固定，播放控件不因状态切换移动。

Logo 图像控件保留 MATRIX 等比缩放与左下对齐，新增 16dp 四边内边距。缩放矩阵按内框宽高计算，保留完整图案与阴影空间。服务端已存在裁去透明画布的 180／360／720 Logo 变体，本轮没有修改后端或原图。

## 验证

- 修改前可控慢 Logo 请求复现：期望片名 INVISIBLE，实际 VISIBLE。
- 独立只读模拟器中使用合成服务，2 项仪器测试通过：Logo 与背景并发、加载不闪文字、控件位置稳定、失败回退、策略切换、竖版比例及 16dp 内边距。
- 截图增加合成帧等待后重新运行，回读确认横版和竖版图像可见且完整。
- 正常 `assembleRelease`、`lintRelease`、`testReleaseUnitTest` 通过；126 项单元测试，无失败、错误或跳过。Lint 无错误，109 条警告。
- `git diff --check` 通过。
- 正式 APK 包名 `xyz.etkn.tv`，minSdk 24，版本 0.1.127／128，签名核验通过。
- 证书 SHA-256：`e02d42443875c3631be48199e0df419b0570fcc421be7ff2b620f4c643e67bd7`。
- 正常构建 APK SHA-256：`ecaead20bdfa8bf3ef8d4d8fbf9f707256a2aa3b133ba4470ab0e6eaf91ae6f6`。

仪器测试仅临时覆盖构建配置，使用独立包 `xyz.etkn.tv.logotest`，未修改正式配置或用户数据。Windows dex 锁按既有 SOP 在核实无活动构建后，优雅停止相关空闲 Gradle daemon；离线依赖检查改用已有完整缓存，最终正常构建成功。

## 证据与边界

- [仪器测试结果](evidence/2026-11-17-detail-logo/instrumentation.txt)
- [正常构建检查](evidence/2026-11-17-detail-logo/release-checks.txt)
- [背景仍悬挂时 Logo 已显示](evidence/2026-11-17-detail-logo/slow-backdrop.png)
- [竖版完整显示](evidence/2026-11-17-detail-logo/portrait.png)
- [Web 与变体调查报告](D:/work/ETKvNext/docs/reports/2026-11-17-detail-logo-rendering.md)

未执行推送、发布或真实电视验收。没有实际 Logo／背景网络前后测速，不能将首次变体生成作为全部速度差异的定论。不同宽高比在完整等比适配时仍有必要留白。
