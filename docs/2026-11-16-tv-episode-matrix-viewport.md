# TV 分集矩阵滚动边界修复（0.1.120）

## 目标与结论

修复分集数字矩阵向下滚动后，已滚出的集数越过顶部并覆盖分集剧照和简介的问题；通过 ADB 覆盖更新 192.168.31.188。

基线 0.1.119 在模拟器的 96 集测试中复现：部分单元格的全局可见矩形超出矩阵视口，截图可见第 43～70 集覆盖标题、剧照和简介。原滚动层设置 clipChildren 仍不能形成独立的固定裁剪边界。

## 实际变更

- DetailActivity.showSeason 为矩阵增加独立透明 FrameLayout，按自身尺寸设置显式 clipBounds，同时裁剪子项及其外侧阴影；原 ScrollView 只在框内滚动。
- 网格上下各保留 FocusScroll.MARGIN_DP（12dp），保留原行高、列宽、最大224dp视口、分页触底加载、季切换与焦点锁定逻辑。
- 增加96集长矩阵上下导航回归，检查后续页加载、预览固定、全部可见单元格均在框内及首行返回。
- 测试夹具补齐现有 PlaybackTarget/EpisodePage 接口；旧焦点测试改从现有季海报进入矩阵，旧单季测试移除对已取消播放按钮的依赖。
- 版本递增到0.1.120 / versionCode 121。正式签名沿用原证书。服务端无改动。

## 验证

- 基线回归：1项失败，断言“可见分集必须限制在矩阵框内”；修复后同用例通过。已目视核对修复前后截图。
- Gradle 8.13 assembleRelease、lintRelease、testReleaseUnitTest、assembleReleaseAndroidTest通过；121项单元测试，无失败、错误、跳过。
- 模拟器5项相关仪器回归最终均通过：长矩阵边界、单季预览、外部进入首集/续播及已观看状态、多季切换、占位日期行对齐。首轮5项中1项因旧音轨按钮不存在失败；更新为季海报入口后，该项单独回归OK (1 test)，其余4项原轮通过。
- 应用及测试APK均使用既有正式证书，SHA-256 e02d42443875c3631be48199e0df419b0570fcc421be7ff2b620f4c643e67bd7；应用v2签名校验通过，包名xyz.etkn.tv、最低API24、target35。
- 应用APK SHA-256：7f64faed5e7f526b7b93a06d20a0eb3131f752b540969cc5ece513a2b2dd7cff。
- ADB向192.168.31.188:5555执行install -r返回Success，设备回读0.1.120 / 121。通过导出的SetupActivity启动Status: ok，约3秒后仍在SetupActivity；启动后crash缓冲未检出ETKN FATAL EXCEPTION。

## 产物与边界

正式APK和前后截图位于 D:\work\ETKN-TV\outputs\0.1.120-matrix-viewport\。真实电视停留在用户选择登录入口；真实账号下遥控器连续上下选集尚待用户验收。未进行GitHub推送或Release发布。

仪器测试仅在模拟器运行，未在真实电视运行会保存/清理合成会话的测试。正式电视数据未清除。

本地命令沙箱初始化失败，未执行文件操作；通过已获审批的沙箱外命令完成。测试构建首次R8报告测试注解缺失，补入已缓存的error_prone_annotations:2.30.0后，仅剩JDK编译期Modifier引用，按生成规则在临时testProguardFiles中限定忽略此类型，最终测试包构建通过。相关脚本作为验证产物保存，不修改正式依赖与混淆规则。用户已有wrapper配置及其他outputs内容保留。

## 可复用规则

分集矩阵必须有不随内部滚动移动的独立裁剪父层；只设置ScrollView.clipChildren或提高预览层级不足以证明边界生效。验证应同时检查实际截图、全局可见矩形、外层滚动不变和矩阵内部滚动确实发生。
