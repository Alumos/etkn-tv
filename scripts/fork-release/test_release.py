"""Offline tests: Git fixtures in temporary dirs, no APK tools or GitHub calls."""

import base64
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import Mock, patch

import release


class MergeTests(unittest.TestCase):
    def setUp(self):
        tmp = TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.git = release.Git(self.root)
        self.git.run("init", "--quiet")
        self.git.run("config", "user.name", "Test")
        self.git.run("config", "user.email", "test@example.invalid")
        self.common = self.commit("shared.txt", "common\n")

    def commit(self, path, content):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(content, encoding="utf-8")
        self.git.run("add", path)
        self.git.run("commit", "--quiet", "-m", path)
        return self.git.value("rev-parse", "HEAD")

    def detach(self, ref):
        self.git.run("checkout", "--quiet", "--detach", ref)

    def test_existing_upstream_ancestor_keeps_fork_head(self):
        fork = self.commit("fork.txt", "revised\n")
        self.assertEqual(self.git.merge(fork, self.common), fork)

    def test_divergence_creates_same_two_parent_commit_without_checkout(self):
        fork = self.commit("fork.txt", "revised\n")
        self.detach(self.common)
        upstream = self.commit("upstream.txt", "release\n")
        result = self.git.merge(fork, upstream)
        self.assertEqual(self.git.merge(fork, upstream), result)
        self.assertEqual(self.git.value("show", "-s", "--format=%P", result), f"{fork} {upstream}")
        self.assertEqual(self.git.value("show", f"{result}:fork.txt"), "revised")
        self.assertEqual(self.git.value("show", f"{result}:upstream.txt"), "release")
        self.assertEqual(self.git.value("rev-parse", "HEAD"), upstream)
        self.assertEqual(self.git.value("status", "--porcelain"), "")

    def test_upstream_ahead_is_merged_without_rewriting_history(self):
        upstream = self.commit("upstream.txt", "release\n")
        result = self.git.merge(self.common, upstream)
        self.assertEqual(self.git.value("show", "-s", "--format=%P", result), f"{self.common} {upstream}")
        self.assertTrue(self.git.ancestor(upstream, result))

    def test_conflict_aborts_without_moving_head(self):
        fork = self.commit("shared.txt", "fork\n")
        self.detach(self.common)
        upstream = self.commit("shared.txt", "upstream\n")
        with patch("builtins.print"), self.assertRaisesRegex(SystemExit, "Merge failed"):
            self.git.merge(fork, upstream)
        self.assertEqual(self.git.value("status", "--porcelain"), "")
        self.assertEqual(self.git.value("rev-parse", "HEAD"), upstream)

    def test_workflow_history_rejected_even_if_content_was_reverted(self):
        fork = self.commit("fork.txt", "revised\n")
        self.detach(self.common)
        workflow = self.commit(".github/workflows/test.yml", "name: test\n")
        self.git.run("revert", "--no-edit", workflow)
        upstream = self.git.value("rev-parse", "HEAD")
        with self.assertRaisesRegex(SystemExit, "history changes .github/workflows"):
            self.git.merge(fork, upstream)

    def test_pinned_sources_and_dependency_block(self):
        base = self.commit("app/build.gradle", "versionCode 131\ndependencies {\n    testImplementation 'a:b:1'\n}\n")
        next_version = self.commit("app/build.gradle", "versionCode 132\ndependencies {\n    testImplementation 'a:b:1'\n}\n")
        changed_dependency = self.commit("app/build.gradle", "versionCode 132\ndependencies {\n    testImplementation 'a:b:2'\n}\n")
        self.detach(next_version)
        changed_native = self.commit("app/src/main/jniLibs/test.so", "native fixture\n")
        with patch.multiple(release, NATIVE_COMMIT=base, DEPENDENCY_COMMIT=base):
            self.git.verify(base, next_version)
            with self.assertRaisesRegex(SystemExit, "dependency declarations changed"):
                self.git.verify(base, changed_dependency)
            with self.assertRaisesRegex(SystemExit, "Native inputs changed"):
                self.git.verify(base, changed_native)


class MetadataTests(unittest.TestCase):
    def test_planning_uses_job_token_for_api_not_public_assets(self):
        git = Mock()
        git.prepare.return_value = ("a" * 40, "b" * 40, "c" * 40)
        git.remote_tag.return_value = None
        git.value.return_value = "same-automation"
        git.merge.return_value = "d" * 40
        official = dict(tag_name="v0.1.131", draft=False, prerelease=False, published_at="2026-01-01")
        manifest = dict(packageName="xyz.etkn.tv", versionName="0.1.131", versionCode=132)
        with (
            patch.object(release, "Git", return_value=git),
            patch.object(release, "api", side_effect=[official, {"object": {"sha": "b" * 40}}, None, None]) as api,
            patch.object(release, "read_url", return_value=json.dumps(manifest).encode()) as read,
        ):
            plan = release.plan_release(git, "v0.1.131", "a" * 40, "job-token")
        self.assertTrue(plan["needed"])
        self.assertEqual(api.call_count, 4)
        for call in api.call_args_list:
            self.assertEqual(call.kwargs["token"], "job-token")
        read.assert_called_once_with(release.asset_url(release.UPSTREAM, "v0.1.131", "update.json"))


class PublishTests(unittest.TestCase):
    def setUp(self):
        tmp = TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        self.signed = root / "fixture.apk"
        self.signed.write_bytes(b"not a real APK\n" * 10_000)
        self.output = root / "assets"
        self.digest = release.file_digest(self.signed)
        self.signer = "12" * 32
        self.plan = dict(fork_tag="revised-v0.1.131", upstream_tag="v0.1.131", version="0.1.131", code=132,
                         source="a" * 40, upstream="b" * 40, previous_signer_b64="")
        self.marker = (f"<!-- fork-release-v1 tag=revised-v0.1.131 source={self.plan['source']} "
                       f"upstream={self.plan['upstream']} -->")
        self.git = Mock()
        self.git.remote_tag.return_value = self.plan["source"]
        self.apk_name = "etkn-tv-revised-0.1.131.apk"

    def fake_download(self, url, dest, expected, limit):
        dest.write_bytes(b"fixture native sources")

    def test_signer_continuity_rejected_before_any_mutations(self):
        self.plan["previous_signer_b64"] = base64.b64encode(bytes.fromhex("34" * 32)).decode()
        with patch.object(release, "api") as api, self.assertRaisesRegex(SystemExit, "previous public release"):
            release.publish(self.git, self.plan, self.signed, self.output, self.digest, self.signer, "test-token")
        api.assert_not_called()
        self.git.push.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_wrong_existing_asset_is_not_deleted_replaced_or_published(self):
        draft = dict(tag_name=self.plan["fork_tag"], draft=True, body=self.marker, assets=[dict(
            name=self.apk_name, state="uploaded", size=self.signed.stat().st_size, digest="sha256:" + "0" * 64)])
        with patch.object(release, "download", self.fake_download), patch.object(release, "api", return_value=[draft]) as api, \
                patch.object(release, "read_url") as read, self.assertRaisesRegex(SystemExit, "No asset will be replaced"):
            release.publish(self.git, self.plan, self.signed, self.output, self.digest, self.signer, "test-token")
        self.git.push.assert_not_called()
        read.assert_not_called()
        self.assertEqual(api.call_count, 1)
        self.assertNotIn("method", api.call_args.kwargs)

    def test_verified_draft_reuses_matching_asset_and_publishes_last(self):
        draft = dict(id=17, tag_name=self.plan["fork_tag"], draft=True, prerelease=False,
                     name="ETKN TV Revised 0.1.131 (upstream v0.1.131)", body=self.marker, assets=[dict(
                         name=self.apk_name, state="uploaded", size=self.signed.stat().st_size, digest="sha256:" + self.digest)])
        draft["body"] += (f"\n\n{draft['name']}\n\n"
                          "- [Upstream release](https://github.com/hbq0405/etkn-tv/releases/tag/v0.1.131)\n"
                          f"- [Corresponding application source](https://github.com/Alumos/etkn-tv/tree/{self.plan['source']}) (GPL-3.0)\n"
                          f"- The attached {release.NATIVE_NAME} contains the corresponding native sources, licenses and rebuild materials.\n")
        events = []
        self.git.push.side_effect = lambda *args: events.append("push")

        def api(repo, endpoint, *, token, method="GET", payload=None):
            if endpoint.startswith("releases?"):
                return [draft]
            self.assertEqual(endpoint, "releases/17")
            if method == "PATCH":
                self.assertEqual(payload, dict(draft=False, make_latest="true"))
                events.append("publish")
            else:
                self.assertEqual(method, "GET")
            return draft

        def upload(url, *, method, data, token, content_type):
            self.assertEqual(method, "POST")
            name = url.split("?name=")[1]
            self.assertNotEqual(name, self.apk_name)
            self.assertEqual(data, (self.output / name).read_bytes())
            draft["assets"].append(dict(name=name, state="uploaded", size=len(data),
                                        digest="sha256:" + release.file_digest(self.output / name)))
            events.append(name)

        with patch.object(release, "download", self.fake_download), patch.object(release, "api", api), \
                patch.object(release, "read_url", upload), patch.object(release, "public_verify") as verify:
            release.publish(self.git, self.plan, self.signed, self.output, self.digest, self.signer, "test-token")
        self.assertEqual(events, ["push", "update.json", release.NATIVE_NAME, "SHA256SUMS.txt", "publish"])
        verify.assert_called_once()

    def test_verified_public_retry_uses_canonical_manifest_without_writing_remote(self):
        with patch.object(release, "download", self.fake_download), patch.object(release, "public_verify") as verify, \
                patch.object(release, "api") as api:
            release.publish(self.git, self.plan, self.signed, self.output, self.digest, self.signer, "test-token",
                            already_published=True)
        api.assert_not_called()
        self.git.push.assert_not_called()
        verify.assert_called_once()
        manifest = json.loads((self.output / "update.json").read_bytes())
        self.assertEqual(manifest["packageName"], "xyz.etkn.tv.revised")
        self.assertEqual(manifest["signerSha256"], self.signer)
        self.assertEqual(manifest["apkUrl"], "https://github.com/Alumos/etkn-tv/releases/download/revised-v0.1.131/" + self.apk_name)


if __name__ == "__main__":
    unittest.main()
