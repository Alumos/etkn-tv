# Fork 发布工作流

- 仅在 `Alumos/etkn-tv` 的 `main` 运行。每 6 小时查询上游最新正式 Release；手动触发可填写精确的 `vMAJOR.MINOR.PATCH`，留空同定时行为。只接收可从上游 `main` 追溯的标签，Fork 标签独立为 `revised-vMAJOR.MINOR.PATCH`；发布后重复执行不修改已发布资产。不会自动补发跳过的旧版本。
- 已发布当前上游基线后，手动输入该 `upstream_tag` 和按顺序递增的 `fork_revision`（1–99）可发 `revised-vMAJOR.MINOR.PATCH-rN`。APK 版本号取上游值与上一 Fork APK + 1 中的较大者，基线号另存于清单；Gradle 只在云端构建时接收实际 APK 版本，不改 Git 中上游的基线值。
- 仓库 Secrets：`FORK_KEYSTORE_BASE64`（固定正式 Keystore 的 Base64）、`FORK_KEYSTORE_PASSWORD`、`FORK_KEY_ALIAS`、`FORK_KEY_PASSWORD`、`FORK_SIGNER_SHA256`（签名证书 SHA-256，非 Keystore 文件摘要）。后续版本必须与已发布清单中的 `signerSha256` 匹配。Gradle 的 `ETKN_TV_SIGNING_PROPERTIES` 接口不变；CI 构建不接触签名密钥，专用 job 只签名与校验，不检出候选代码。临时产物保留 1 天，不安装、不发布未签名包。
- 单测、Lint、包名/版本、签名与哈希校验通过后，发布 job 才以普通原子推送更新 `main` 和 Fork 标签，再发布通过资产校验的草稿。需要允许 GitHub Actions 的内置 `GITHUB_TOKEN` 写入该仓库内容；不会绕过分支保护、标签规则或工作流文件权限。若上游改动工作流（包括新增后撤销）、原生输入或依赖基线，先人工审阅、合并必要更改并更新相应校验记录。
- 若推送成功但发布失败，可在产物仍有效时用 **Re-run failed jobs** 复用已验证 APK。工作流只复用它自己创建且资产哈希、大小均匹配的草稿；不删除/覆盖资产或回退远程引用。若遇到不同资产、签名变化或冲突，先由维护者检查，不要重复签名或强推覆盖。
- `update.json` 保存官方 GitHub 下载地址，Android 客户端负责拼接镜像。Release 附带 APK、`update.json`、`SHA256SUMS.txt` 和固定校验的原生对应源码 ZIP，发行说明指向构建的精确提交。

离线验证（不生成 APK、不连接 GitHub）：

```bash
python3 -B -m unittest discover -s scripts/fork-release -v
```
