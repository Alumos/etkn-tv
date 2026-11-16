# TV 详情片名粗黑字体与缺失评分隐藏

2026-11-16；0.1.124 / versionCode 125；指定电视 192.168.31.188:5555。

## 需求及修改

用户反馈 TV 详情回退片名没有 Web 的粗黑效果，并要求不要外部网页链接，没有评分时隐藏来源图标。

Web 全局 h1 字重为 720，字体栈为 Inter、Segoe UI、Microsoft YaHei、sans-serif；TV 原先只有系统 BOLD。指定电视的 fonts.xml 中文回退只有常规字重，不能靠设置系统粗体保证相同中文效果。

- 仅详情主片名改用随包内置的思源黑体 CN Heavy（真实 900 字重），支持简繁体中文与英文。保留 26sp 字号、最多两行及省略、底部对齐、4:1 标题/Logo 区域，切换 Logo 不移动播放按钮。
- 豆瓣、IMDb、TMDb 评分缺失、非数值或非正数时，图标和分数一起 GONE；所有评分均缺失时收起原固定 36dp 评分行，避免空白。服务端提供真实烂番茄 0% 时保留既有有效零分语义。
- 详情原本没有外部网页按钮或 ACTION_VIEW 跳转，继续只显示不可聚焦、不可点击的本地评分标识；不渲染 ExternalUrls。内部题材、标签、合集、人物入口保持可用。
- 不改 Web、服务端、选图、字幕翻译、播放与原生库；未改动用户已有 Gradle Wrapper。

## 字体来源与许可

使用 Adobe Source Han Sans 2.005R 官方原始 SubsetOTF/CN/SourceHanSansCN-Heavy.otf，8,808,068 字节。固定上游提交 6c709ca72d3d7c46ab42ebecc1a26e7d69595a37，下载后核验 Git blob；无修改、无裁剪。OS/2 字重 900，cmap12 含 30,926 个码位；已核验代表性简繁体字形及英文字母。

上游：https://github.com/adobe-fonts/source-han-sans/tree/2.005R

- 字体：app/src/main/res/font/source_han_sans_cn_heavy.otf
- 字体 SHA-256：88c749b0a54a0800124ded6544e399302ed224aa49992ea364b88769f825c54c
- 完整上游版权及 SIL OFL 1.1：app/src/main/assets/licenses/SourceHanSans-OFL-1.1.txt；应用开源许可自动列出。
- 完整字体与许可已逐字节核对 APK 内资源，来源与哈希保存于交付目录 font-source.json。

## 验证与安装

- git diff --check 通过；正式签名 Release 构建，123 项单元测试通过，0 失败；Lint 0 errors、110 warnings。
- 正式证书 SHA-256：e02d42443875c3631be48199e0df419b0570fcc421be7ff2b620f4c643e67bd7。20 个 JNI 库与 0.1.123 字节哈希相同。
- 指定电视使用同正式证书、独立 applicationId xyz.etkn.tv.fontverify，4 项仪器测试最终 OK：简繁/英文长片名与固定区域、竖版 Logo 等比及动态回退；评分缺失/恢复、整行收起、有效烂番茄 0% 与无外部入口；六行简介/更多/轨道导航；实时翻译提示及手动关闭。正式应用会话未用作测试。
- 人工回读短中文、长简繁中文、长英文、无评分及部分评分截图。初次检查发现图标全部隐藏后原固定评分容器仍占 36dp，补上整行隐藏后完整复测通过，初始失败日志保留。
- 独立测试应用和测试包均已卸载。正式 adb install -r 返回 Success；回读 0.1.124/code125，已安装 base.apk 与交付 APK 的 SHA-256 一致；正式 SetupActivity 启动 Status: ok。
- 最终 APK SHA-256：5a4216fd04deb5dad6437398872b9850275b65bf0d5542aa99921d65604826ed；70,005,009 字节。
- APK、清单、签名构建日志、安装/版本/哈希/启动回执、4 项测试日志和 9 张截图位于 outputs/0.1.124-detail-font-ratings/。

## 边界

已验证指定电视合成详情及正式 APK 安装/启动；用户真实影片最终观感仍由用户验收。仅本地提交，未推送、未发布。字体效果不依赖电视中文系统字体，也不承诺与每台 Web 浏览器的系统字体字形完全相同。
