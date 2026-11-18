# ETKN TV Revised：上游同步与发布

## 首次启用

1. 审核改动后，把本工作流提交到 [Alumos/etkn-tv](https://github.com/Alumos/etkn-tv) 的默认分支 `main`。在 Fork 的 **Actions** 中启用工作流；如 Actions 提示 Fork 默认禁用，先在仓库设置中允许 Actions。只使用 GitHub 官方托管 Linux runner，不在用户的 Mac 上运行 Gradle 或编译 APK。
2. 为 Revised 创建一套**独立且长期使用的 Android 正式签名**（JKS，可用可信的 JDK/keytool 环境生成）。离线备份原始 keystore 和密码；不要提交、粘贴到聊天或在 Actions 日志打印它们。不能用上游、Android Debug 或每次运行临时生成的密钥。
3. 在 Fork 的 **Settings → Secrets and variables → Actions** 添加下列 repository secrets。证书摘要是固定身份校验值，不是密码；应来自离线正式证书，不从待发布 APK 临时倒推。

   | Secret | 内容 |
   | --- | --- |
   | `FORK_KEYSTORE_BASE64` | 正式 JKS 文件字节的单行 Base64；不可写入工作树。 |
   | `FORK_KEYSTORE_PASSWORD` | Keystore 密码。 |
   | `FORK_KEY_ALIAS` | 用于签名的 key alias。 |
   | `FORK_KEY_PASSWORD` | 对应私钥密码。 |
   | `FORK_SIGNER_SHA256` | 正式签名证书的 SHA-256。 |

4. 在 [Actions](https://github.com/Alumos/etkn-tv/actions) 手动运行工作流，分支选择 `main`。留空 `upstream_tag` 使用上游 latest 正式 Release；也可填一个明确的正式标签，如 `v0.1.131`。已经发布同版或不比 Fork 当前版更新的版本不会覆盖现有版本。第一次发版成功后，从 [Revised Releases](https://github.com/Alumos/etkn-tv/releases) 手动把签名 APK 装到电视。原版 `xyz.etkn.tv` 和 Revised `xyz.etkn.tv.revised` 可共存，各自保留本地数据；Revised 首次使用需登录。

## 自动更新的边界

- 定时任务查询上游 latest 正式 Release，只处理确认属于上游 `main` 历史的发布标签。不逐个补发跳过的版本，也不为同一已发布版本覆盖 APK。Git 合并保留 Fork 改动，不强制覆盖主分支；冲突、上游工作流更新或需重新核对的原生播放器源码变动会停发，应先审阅处理再重试。普通同包升级依赖递增的 `versionCode` 和同一正式签名；版本不同但 `versionCode` 不增加，也不能覆盖。
- 在无发行私钥的 runner 执行 Gradle、单元测试和 Lint。未签名 APK 只是传给隔离签名任务的短期 Actions artifact，不可安装，不是 Release 资产。签名任务不执行合并后的 Gradle 或上游代码；单独的发布任务持有仓库写权限但不持有私钥。
- 发布标签使用 `revised-v…`，与上游标签区分。应用源码对应 Fork 发布标签，GitHub 生成源码归档；发行同时保留相符的原生播放器对应源码、原始许可及重建材料。若上游原生依赖、二进制或重建脚本变化，先核实并更新对应源码包和摘要，不继续自动发布旧材料。不要手动移动已发布的标签或原地替换 APK。
- 首版记录公开证书的 SHA-256；以后签名证书必须与上一版一致，不能通过同时更改密钥与 Secrets 里的指纹绕过。若任务在创建草稿后失败，优先使用 **Re-run failed jobs** 复用原签名产物（保留一天）；已上传资产不匹配时停发，不自动删除或替换。
- `update.json` 内记录 Fork 的规范 GitHub APK URL。应用从 `https://gh-proxy.com/https://github.com/Alumos/etkn-tv/releases/latest/download/update.json` 检查更新，并给合法的 Fork APK 地址加同一镜像前缀。只访问指定镜像，不跟随跨主机重定向，不自动回退直连。包大小、SHA-256、包名、递增版本和已安装 APK 的签名仍须全部匹配。镜像有短时缓存或不可用时，请稍后重试。
- GitHub [公开仓库标准 Runner 计费说明](https://docs.github.com/en/billing/concepts/product-billing/github-actions)：公开仓库使用标准 GitHub 托管 runner 免费。[定时事件说明](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule)：工作流每 6 小时检查一次，只跑默认分支，调度可能延迟。公开 Fork 的定时工作流默认禁用；连续 60 天无仓库活动后也可能自动停用。可到 Actions 重新启用或手动触发。

## 发布后验证

- 在 Actions 确认单测、Lint、构建、签名与 Release 校验均成功，确认 GitHub 的 latest 指向正确的 `revised-v…` 标签。
- 匿名请求镜像清单，确认是 Fork 包名和目标版本；从镜像下载签名 APK，核对大小和 SHA-256。
- 电视上确认首版能和原版一起安装。下一版发布后，在 Revised 内检查、下载并覆盖安装，确认官方包不受影响、Revised 的本地数据保留。CI 通过不能代替真机测试。
