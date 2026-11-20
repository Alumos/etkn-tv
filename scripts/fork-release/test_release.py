"""Offline tests: Git fixtures in temporary dirs, no APK tools or GitHub calls."""

import base64
import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import Mock, patch

import release


class GitFixture(unittest.TestCase):
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


class MergeTests(GitFixture):
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

    def test_upstream_literal_gradle_baseline_remains_in_git_across_merge(self):
        gradle_131 = ("android {\n    defaultConfig {\n        versionCode 132\n"
                      "        versionName '0.1.131'\n    }\n}\n")
        gradle_132 = gradle_131.replace("versionCode 132", "versionCode 133").replace("'0.1.131'", "'0.1.132'")
        upstream_131 = self.commit("app/build.gradle", gradle_131)
        fork = self.commit("fork.txt", "revised\n")
        self.detach(upstream_131)
        upstream_132 = self.commit("app/build.gradle", gradle_132)
        source = self.git.merge(fork, upstream_132)
        script = self.git.value("show", f"{source}:app/build.gradle")
        release.verify_upstream_gradle(script, "0.1.132", 133)
        self.assertEqual(script, gradle_132.rstrip())
        with self.assertRaisesRegex(SystemExit, "Merged Gradle base version"):
            release.verify_upstream_gradle(script, "0.1.132-r1", 134)
        self.assertEqual(self.git.value("show", f"{fork}:app/build.gradle"), gradle_131.rstrip())
        self.assertEqual(self.git.value("rev-parse", "HEAD"), upstream_132)
        self.assertEqual(self.git.value("status", "--porcelain"), "")


class SnapshotTests(GitFixture):
    def bridge_fixture(self):
        tag = "v0.1.135"
        fork = self.commit("fork.txt", "revised\n")
        self.detach(self.common)
        added = self.commit(".github/workflows/transient-fixture.yml", "name: fixture only\n")
        self.git.run("revert", "--no-edit", added)
        deleted = self.git.value("rev-parse", "HEAD")
        upstream = self.commit("shared.txt", "release 135\n")
        tag_object = self.annotated_tag(tag, upstream)
        return fork, added, deleted, tag, tag_object, upstream

    def annotated_tag(self, tag, source):
        self.git.run("tag", "-a", "-m", "fixture release", tag, source)
        return self.git.value("rev-parse", f"refs/tags/{tag}")

    def test_pinned_bridge_preserves_true_merge_tree_without_importing_raw_history(self):
        fork, added, deleted, tag, tag_object, upstream = self.bridge_fixture()
        expected_tree = self.git.value("merge-tree", "--write-tree", fork, upstream).splitlines()[0]
        with patch.object(release, "SNAPSHOT_BRIDGE", (tag, tag_object, upstream, self.common)):
            candidate = self.git.merge(fork, upstream, tag, tag_object)
            self.assertEqual(self.git.merge(fork, upstream, tag, tag_object), candidate)
            self.assertTrue(self.git.contains_release(candidate, tag, upstream, tag_object))
        self.assertEqual(self.git.value("rev-parse", candidate + "^{tree}"), expected_tree)
        self.assertEqual(self.git.value("show", "-s", "--format=%P", candidate), fork)
        self.assertEqual(self.git.value("show", f"{candidate}:fork.txt"), "revised")
        self.assertEqual(self.git.value("show", f"{candidate}:shared.txt"), "release 135")
        self.assertIn(f"ETKN-Upstream-Snapshot: {tag} {tag_object} {upstream}",
                      self.git.value("show", "-s", "--format=%B", candidate).splitlines())
        self.assertEqual(self.git.value("ls-tree", "-r", candidate, "--", ".github/workflows"), "")
        for raw in (added, deleted, upstream):
            self.assertFalse(self.git.ancestor(raw, candidate))
        removed_blob = self.git.value("rev-parse", f"{added}:.github/workflows/transient-fixture.yml")
        reachable_objects = {line.split()[0] for line in self.git.value("rev-list", "--objects", candidate).splitlines()}
        self.assertNotIn(removed_blob, reachable_objects)
        self.assertEqual(self.git.value("rev-parse", "HEAD"), upstream)
        self.assertEqual(self.git.value("status", "--porcelain"), "")

    def test_bridge_exception_requires_exact_tag_object_peeled_commit_and_common_base(self):
        fork, _, deleted, tag, tag_object, upstream = self.bridge_fixture()
        other_tag = self.annotated_tag("v0.1.136", upstream)
        other_endpoint = self.commit("upstream.txt", "new endpoint\n")
        for requested_tag, requested_object, target, pinned_base in (
            ("v0.1.136", tag_object, upstream, self.common),
            (tag, other_tag, upstream, self.common),
            (tag, tag_object, other_endpoint, self.common),
            (tag, tag_object, upstream, deleted),
        ):
            with self.subTest(tag=requested_tag, tag_object=requested_object, target=target, base=pinned_base), \
                    patch.object(release, "SNAPSHOT_BRIDGE", (tag, tag_object, upstream, pinned_base)), \
                    self.assertRaises(SystemExit):
                self.git.merge(fork, target, requested_tag, requested_object)

    def test_pinned_bridge_rejects_net_changes_to_protected_paths(self):
        fork, _, _, _, _, upstream = self.bridge_fixture()
        cases = (("v0.1.136", ".github/workflows/new-fixture.yml"),
                 ("v0.1.137", "scripts/fork-release/new-fixture.py"))
        for tag, path in cases:
            with self.subTest(path=path):
                self.detach(upstream)
                target = self.commit(path, "fixture only\n")
                tag_object = self.annotated_tag(tag, target)
                with patch.object(release, "SNAPSHOT_BRIDGE", (tag, tag_object, target, self.common)), \
                        self.assertRaises(SystemExit):
                    self.git.merge(fork, target, tag, tag_object)

    def test_pinned_bridge_does_not_ignore_real_source_conflicts(self):
        fork, _, _, tag, tag_object, upstream = self.bridge_fixture()
        self.detach(fork)
        conflicting_fork = self.commit("shared.txt", "a fork change to the same line\n")
        with patch.object(release, "SNAPSHOT_BRIDGE", (tag, tag_object, upstream, self.common)), \
                patch("builtins.print"), self.assertRaisesRegex(SystemExit, "Merge failed"):
            self.git.merge(conflicting_fork, upstream, tag, tag_object)

    def test_snapshot_base_allows_next_release_to_change_same_line_and_preserves_fork_revision(self):
        fork, added, deleted, tag, tag_object, upstream = self.bridge_fixture()
        with patch.object(release, "SNAPSHOT_BRIDGE", (tag, tag_object, upstream, self.common)):
            source = self.git.merge(fork, upstream, tag, tag_object)
            self.detach(source)
            revised = self.commit("fork.txt", "later fork UI\n")
            self.assertTrue(self.git.contains_release(revised, tag, upstream, tag_object))
            self.assertEqual(self.git.merge(revised, upstream, tag, tag_object), revised)
            self.assertFalse(self.git.contains_release(revised, tag, upstream, "0" * 40))
            with self.assertRaises(SystemExit):
                self.git.merge(revised, upstream, tag, "0" * 40)
            self.detach(upstream)
            next_tag = "v0.1.136"
            next_upstream = self.commit("shared.txt", "release 136\n")
            next_object = self.annotated_tag(next_tag, next_upstream)
            # Graph ancestry still points behind the bridge; using it would conflict on shared.txt.
            self.assertEqual(self.git.value("merge-base", revised, next_upstream), self.common)
            candidate = self.git.merge(revised, next_upstream, next_tag, next_object)
            self.assertEqual(self.git.merge(revised, next_upstream, next_tag, next_object), candidate)
            self.assertTrue(self.git.contains_release(candidate, next_tag, next_upstream, next_object))
        self.assertEqual(self.git.value("show", "-s", "--format=%P", candidate), revised)
        self.assertEqual(self.git.value("show", f"{candidate}:fork.txt"), "later fork UI")
        self.assertEqual(self.git.value("show", f"{candidate}:shared.txt"), "release 136")
        for raw in (added, deleted, upstream, next_upstream):
            self.assertFalse(self.git.ancestor(raw, candidate))

    def test_later_reverted_protected_history_is_not_exempted(self):
        fork, _, _, tag, tag_object, upstream = self.bridge_fixture()
        with patch.object(release, "SNAPSHOT_BRIDGE", (tag, tag_object, upstream, self.common)):
            source = self.git.merge(fork, upstream, tag, tag_object)
            for path in (".github/workflows/future-fixture.yml", "scripts/fork-release/future-fixture.py"):
                with self.subTest(path=path):
                    self.detach(upstream)
                    added = self.commit(path, "fixture only\n")
                    self.git.run("revert", "--no-edit", added)
                    later = self.git.value("rev-parse", "HEAD")
                    self.assertEqual(self.git.value("diff", "--name-only", upstream, later, "--", path), "")
                    with self.assertRaises(SystemExit):
                        self.git.merge(source, later, "v0.1.136", later)

    def test_rewritten_upstream_cannot_continue_snapshot_history(self):
        fork, _, _, tag, tag_object, upstream = self.bridge_fixture()
        with patch.object(release, "SNAPSHOT_BRIDGE", (tag, tag_object, upstream, self.common)):
            source = self.git.merge(fork, upstream, tag, tag_object)
            self.detach(self.common)
            rewritten = self.commit("upstream.txt", "not a descendant of the published checkpoint\n")
            revised_tag = "v0.1.136"
            revised_object = self.annotated_tag(revised_tag, rewritten)
            with self.assertRaises(SystemExit):
                self.git.merge(source, rewritten, revised_tag, revised_object)


class MetadataTests(unittest.TestCase):
    signer = "34" * 32

    def plan(self, *, upstream_tag="v0.1.131", upstream_code=132, fork_revision=None,
             previous_version=None, previous_code=None, previous_upstream_code=None,
             previous_upstream_tag=None, git_code=None, scheduled=False, published=False):
        git = Mock()
        base, tag_object, upstream, source = (character * 40 for character in "abcd")
        git.prepare.return_value = (base, tag_object, upstream)
        git.remote_tag.return_value = source if published else None
        git.ancestor.return_value = True
        git.contains_release.return_value = True
        git.merge.return_value = source
        upstream_version = upstream_tag[1:]
        gradle_code = upstream_code if git_code is None else git_code
        gradle = f"android {{\n    defaultConfig {{\n        versionCode {gradle_code}\n        versionName '{upstream_version}'\n    }}\n}}"

        def git_value(operation, *args):
            if operation == "rev-parse":
                return source if args[0].startswith("refs/fork-release/") else "same-automation"
            self.assertEqual(operation, "show")
            self.assertEqual(args, (f"{source}:app/build.gradle",))
            return gradle

        git.value.side_effect = git_value
        official = dict(tag_name=upstream_tag, draft=False, prerelease=False, published_at="2026-01-01")
        official_url = release.asset_url(release.UPSTREAM, upstream_tag, "update.json")
        manifests = {official_url: dict(packageName="xyz.etkn.tv", versionName=upstream_version,
                                        versionCode=upstream_code)}
        latest = None
        if previous_version is not None:
            latest_tag = "revised-v" + previous_version
            latest = dict(tag_name=latest_tag, draft=False, prerelease=False, published_at="2026-01-01")
            previous = dict(packageName=release.PACKAGE, versionName=previous_version, versionCode=previous_code,
                            signerSha256=self.signer)
            if previous_upstream_code is not None:
                previous["upstreamVersionCode"] = previous_upstream_code
            if previous_upstream_tag is not None:
                previous["upstreamTag"] = previous_upstream_tag
            manifests[release.asset_url(release.FORK, latest_tag, "update.json")] = previous

        def read_asset(url):
            return json.dumps(manifests[url]).encode()

        already = dict(tag_name="revised-" + upstream_tag, draft=False, prerelease=False) if published else None
        responses = [official, {"object": {"sha": tag_object}}, already, latest]
        with (
            patch.object(release, "Git", return_value=git),
            patch.object(release, "api", side_effect=responses) as api,
            patch.object(release, "read_url", side_effect=read_asset) as read,
            patch("builtins.print"),
        ):
            args = (git, "" if scheduled else upstream_tag, base, "job-token")
            kwargs = {} if fork_revision is None else {"fork_revision": fork_revision}
            plan = release.plan_release(*args, **kwargs)
        if published:
            git.contains_release.assert_called_once_with(source, upstream_tag, upstream, tag_object)
        return plan, api, read

    def test_planning_uses_job_token_for_api_not_public_assets(self):
        plan, api, read = self.plan(scheduled=True)
        self.assertTrue(plan["needed"])
        self.assertEqual((plan["fork_tag"], plan["version"], plan["code"], plan["upstream_code"], plan["fork_revision"]),
                         ("revised-v0.1.131", "0.1.131", 132, 132, 0))
        self.assertEqual(api.call_count, 4)
        self.assertEqual(api.call_args_list[0].args, (release.UPSTREAM, "releases/latest"))
        for call in api.call_args_list:
            self.assertEqual(call.kwargs["token"], "job-token")
        read.assert_called_once_with(release.asset_url(release.UPSTREAM, "v0.1.131", "update.json"))

    def test_schedule_leaves_published_canonical_tag_untouched_after_fork_revision(self):
        plan, api, read = self.plan(scheduled=True, published=True, previous_version="0.1.131-r1",
                                    previous_code=133, previous_upstream_code=132, previous_upstream_tag="v0.1.131")
        self.assertEqual(plan, {"needed": False})
        self.assertEqual(api.call_args.args, (release.FORK, "releases/tags/revised-v0.1.131"))
        self.assertEqual(api.call_count, 3)
        read.assert_not_called()

    def test_first_fork_revision_migrates_legacy_manifest(self):
        plan, _, _ = self.plan(fork_revision=1, previous_version="0.1.131", previous_code=132)
        self.assertTrue(plan["needed"])
        self.assertEqual(plan["upstream_tag"], "v0.1.131")
        self.assertEqual(plan["fork_tag"], "revised-v0.1.131-r1")
        self.assertEqual(plan["version"], "0.1.131-r1")
        self.assertEqual(plan["upstream_code"], 132)
        self.assertEqual(plan["code"], 133)
        self.assertEqual(plan["fork_revision"], 1)
        self.assertEqual(base64.b64decode(plan["previous_signer_b64"]).hex(), self.signer)

    def test_next_fork_revision_compares_upstream_code_not_published_code(self):
        plan, _, _ = self.plan(fork_revision=2, previous_version="0.1.131-r1", previous_code=133,
                               previous_upstream_code=132, previous_upstream_tag="v0.1.131")
        self.assertEqual((plan["fork_tag"], plan["version"], plan["code"], plan["upstream_code"], plan["fork_revision"]),
                         ("revised-v0.1.131-r2", "0.1.131-r2", 134, 132, 2))

    def test_next_upstream_returns_to_canonical_tag_with_monotonic_android_code(self):
        for upstream_code, expected_code in ((133, 135), (140, 140)):
            with self.subTest(upstream_code=upstream_code):
                plan, _, _ = self.plan(upstream_tag="v0.1.132", upstream_code=upstream_code, scheduled=True,
                                       previous_version="0.1.131-r2", previous_code=134,
                                       previous_upstream_code=132, previous_upstream_tag="v0.1.131")
                self.assertEqual((plan["fork_tag"], plan["version"], plan["code"], plan["upstream_code"], plan["fork_revision"]),
                                 ("revised-v0.1.132", "0.1.132", expected_code, upstream_code, 0))

    def test_newer_upstream_code_must_increase_even_if_android_code_could_increase(self):
        for upstream_code in (131, 132):
            with self.subTest(upstream_code=upstream_code), self.assertRaisesRegex(SystemExit, "would not increase"):
                self.plan(upstream_tag="v0.1.132", upstream_code=upstream_code, previous_version="0.1.131-r1",
                          previous_code=133, previous_upstream_code=132, previous_upstream_tag="v0.1.131")

    def test_same_upstream_version_requires_matching_code_and_sequential_revision(self):
        previous = dict(previous_version="0.1.131-r2", previous_code=134,
                        previous_upstream_code=132, previous_upstream_tag="v0.1.131")
        for fork_revision, upstream_code in ((3, 133), (1, 132), (4, 132)):
            with self.subTest(fork_revision=fork_revision, upstream_code=upstream_code), \
                    self.assertRaisesRegex(SystemExit, "must increment the revision"):
                self.plan(fork_revision=fork_revision, upstream_code=upstream_code, **previous)

    def test_fork_revision_requires_a_published_base(self):
        with self.assertRaisesRegex(SystemExit, "Publish the upstream base release"):
            self.plan(fork_revision=1)

    def test_previous_fork_revision_requires_consistent_upstream_metadata(self):
        for code, tag, message in (
            (None, None, "must record its upstream"),
            (132, None, "Invalid latest Fork upstream metadata"),
            (132, "v0.1.130", "Invalid latest Fork upstream metadata"),
            (134, "v0.1.131", "Invalid latest Fork upstream metadata"),
        ):
            with self.subTest(code=code, tag=tag), self.assertRaisesRegex(SystemExit, message):
                self.plan(fork_revision=2, previous_version="0.1.131-r1", previous_code=133,
                          previous_upstream_code=code, previous_upstream_tag=tag)

    def test_monotonic_android_code_cannot_overflow_play_store_limit(self):
        limit = 2_100_000_000
        with self.assertRaisesRegex(SystemExit, "Android versionCode exceeds its limit"):
            self.plan(fork_revision=1, upstream_code=limit, previous_version="0.1.131", previous_code=limit)

    def test_upstream_manifest_cannot_disagree_with_merged_gradle_code(self):
        with self.assertRaisesRegex(SystemExit, "Merged Gradle base version"):
            self.plan(upstream_code=133, git_code=132)

    def test_fork_revision_requires_integer_in_range_and_explicit_upstream_tag(self):
        for revision in (-1, 100, True, "1"):
            with self.subTest(revision=revision), self.assertRaisesRegex(SystemExit, "require an exact upstream_tag"):
                self.plan(fork_revision=revision)
        with self.assertRaisesRegex(SystemExit, "require an exact upstream_tag"):
            self.plan(scheduled=True, fork_revision=1)


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
                         upstream_code=132, fork_revision=0, source="a" * 40, upstream="b" * 40,
                         previous_signer_b64="")
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
        self.assertEqual(manifest["upstreamTag"], "v0.1.131")
        self.assertEqual(manifest["upstreamVersionCode"], 132)
        self.assertEqual(manifest["apkUrl"], "https://github.com/Alumos/etkn-tv/releases/download/revised-v0.1.131/" + self.apk_name)

    def test_new_fork_revision_publishes_independent_android_and_upstream_identity(self):
        plan = dict(self.plan, fork_tag="revised-v0.1.131-r1", version="0.1.131-r1", code=133, fork_revision=1)
        draft = dict(id=18, tag_name=plan["fork_tag"], draft=True, prerelease=False, assets=[])

        def api(repo, endpoint, *, token, method="GET", payload=None):
            if endpoint.startswith("releases?"):
                return []
            if method == "POST":
                self.assertEqual(endpoint, "releases")
                self.assertEqual(payload["tag_name"], plan["fork_tag"])
                self.assertTrue(payload["draft"])
                draft.update(payload)
            else:
                self.assertEqual(endpoint, "releases/18")
            return draft

        def upload(url, *, method, data, token, content_type):
            name = url.split("?name=")[1]
            draft["assets"].append(dict(name=name, state="uploaded", size=len(data),
                                        digest="sha256:" + hashlib.sha256(data).hexdigest()))

        with patch.object(release, "download", self.fake_download), patch.object(release, "api", api), \
                patch.object(release, "read_url", upload), patch.object(release, "public_verify"):
            release.publish(self.git, plan, self.signed, self.output, self.digest, self.signer, "test-token")
        manifest = json.loads((self.output / "update.json").read_bytes())
        self.assertEqual(manifest["packageName"], "xyz.etkn.tv.revised")
        self.assertEqual((manifest["versionName"], manifest["versionCode"]), ("0.1.131-r1", 133))
        self.assertEqual((manifest["upstreamTag"], manifest["upstreamVersionCode"]), ("v0.1.131", 132))
        self.assertEqual(manifest["apkUrl"], release.asset_url(release.FORK, "revised-v0.1.131-r1", "etkn-tv-revised-0.1.131-r1.apk"))


if __name__ == "__main__":
    unittest.main()
