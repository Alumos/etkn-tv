"""Trusted release planner/publisher. Never import or run code from the merge candidate here."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from time import sleep
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

FORK = "Alumos/etkn-tv"
UPSTREAM = "hbq0405/etkn-tv"
PACKAGE = "xyz.etkn.tv.revised"
NATIVE_COMMIT = "4d4b815c5d2663f791fa30030f0f22f22d1034b6"
NATIVE_NAME = "etkn-tv-player-sources-0.1.80.zip"
NATIVE_SHA256 = "feb29e708baf2313d8ec0886816ba1e93508c4ac1984aa5a486c153db86e420c"
NATIVE_URL = f"https://github.com/{UPSTREAM}/releases/download/v0.1.80/{NATIVE_NAME}"
NATIVE_PATHS = ("app/src/main/jniLibs", "app/src/main/cpp", "third_party", ".gitmodules",
                "scripts/Build-PlayerNative.py", "scripts/Build-BlurayNative.py",
                "scripts/Build-NextlibCompat.py", "scripts/Collect-PlayerSources.py")
VERSION = r"(?:0|[1-9][0-9]{0,8})\.(?:0|[1-9][0-9]{0,8})\.(?:0|[1-9][0-9]{0,8})"
DEPENDENCY_COMMIT = "630b797db5618200e765f7768142c03751663aa9"
DEPENDENCY_PATHS = ("build.gradle", "settings.gradle", "gradle", "gradle.properties", "gradlew", "gradlew.bat")
APK_MAX = 200 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def read_url(url, *, method="GET", data=None, token=None, missing=False, content_type="application/json"):
    request = Request(url, data=data, method=method,
                      headers={"User-Agent": "etkn-tv-fork-release/1", "Content-Type": content_type})
    if token:
        request.add_unredirected_header("Authorization", f"Bearer {token}")
    try:
        with urlopen(request, timeout=120) as response:
            return response.read()
    except HTTPError as error:
        if missing and error.code == 404:
            return None
        raise SystemExit(f"{method} {url} failed: HTTP {error.code}.") from None


def api(repo, suffix, *, missing=False, token=None, method="GET", payload=None):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    raw = read_url(f"https://api.github.com/repos/{repo}/{suffix}", method=method,
                   data=data, token=token, missing=missing)
    return json.loads(raw) if raw else None


def asset_url(repo, tag, name):
    return f"https://github.com/{repo}/releases/download/{tag}/{name}"


def version_manifest(repo, tag, package, version):
    manifest = json.loads(read_url(asset_url(repo, tag, "update.json")))
    code = manifest.get("versionCode")
    require(type(code) is int and 0 < code <= 2_100_000_000 and
            manifest.get("packageName") == package and manifest.get("versionName") == version,
            f"Invalid release version metadata for {repo}@{tag}.")
    return manifest


def download(url, target, expected, limit):
    digest = hashlib.sha256()
    size = 0
    with urlopen(Request(url, headers={"User-Agent": "etkn-tv-fork-release/1"}), timeout=120) as source:
        with target.open("wb") as output:
            while chunk := source.read(1024 * 1024):
                size += len(chunk)
                require(size <= limit, "Download exceeds expected maximum size.")
                digest.update(chunk)
                output.write(chunk)
    require(digest.hexdigest() == expected, f"Download SHA-256 mismatch: {target.name}.")


def file_digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


class Git:
    def __init__(self, directory):
        self.directory = Path(directory).resolve()

    def run(self, *args, check=True, extra_env=None):
        env = os.environ.copy()
        env.pop("GH_TOKEN", None)
        env.pop("GITHUB_TOKEN", None)
        env.update(GIT_TERMINAL_PROMPT="0", GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull)
        env.update(extra_env or {})
        return subprocess.run(["git", "-c", "core.hooksPath=/dev/null", "-c", "credential.helper=",
                               "-C", str(self.directory), *args], env=env, check=check,
                              stdout=subprocess.PIPE, text=True)

    def value(self, *args):
        return self.run(*args).stdout.strip()

    def ancestor(self, older, newer):
        code = self.run("merge-base", "--is-ancestor", older, newer, check=False).returncode
        require(code in (0, 1), "Cannot establish Git ancestry.")
        return code == 0

    def remote_tag(self, tag):
        result = self.value("ls-remote", "--refs", "origin", f"refs/tags/{tag}")
        return result.split()[0] if result else None

    def prepare(self, tag):
        self.directory.mkdir()
        self.run("init", "--quiet")
        self.run("remote", "add", "origin", f"https://github.com/{FORK}.git")
        self.run("remote", "add", "upstream", f"https://github.com/{UPSTREAM}.git")
        self.run("fetch", "--no-tags", "origin", "refs/heads/main:refs/remotes/origin/main")
        self.run("fetch", "--no-tags", "upstream", "refs/heads/main:refs/remotes/upstream/main",
                 f"refs/tags/{tag}:refs/upstream-release/{tag}")
        base = self.value("rev-parse", "refs/remotes/origin/main^{commit}")
        ref = f"refs/upstream-release/{tag}"
        upstream = self.value("rev-parse", ref + "^{commit}")
        require(self.ancestor(upstream, "refs/remotes/upstream/main"),
                "Upstream release tag is not on upstream main.")
        return base, self.value("rev-parse", ref), upstream

    def merge(self, base, upstream):
        if self.ancestor(upstream, base):
            return base
        # Even a later revert could import workflow-changing commits into our repository.
        require(not self.value("log", "--full-history", "--format=%H", f"{base}..{upstream}", "--", ".github/workflows"),
                "Upstream history changes .github/workflows. Review and merge manually before retrying.")
        # merge-tree computes the same three-way merge without checking out candidate files.
        merged = self.run("merge-tree", "--write-tree", base, upstream, check=False)
        if merged.returncode:
            print(merged.stdout.rstrip())
            raise SystemExit("Merge failed; resolve it on Fork main before retrying. No refs were pushed.")
        tree = merged.stdout.splitlines()[0]
        identity = dict(GIT_AUTHOR_NAME="github-actions[bot]", GIT_COMMITTER_NAME="github-actions[bot]",
                        GIT_AUTHOR_EMAIL="41898282+github-actions[bot]@users.noreply.github.com",
                        GIT_COMMITTER_EMAIL="41898282+github-actions[bot]@users.noreply.github.com")
        stamp = max(int(self.value("show", "-s", "--format=%ct", parent)) for parent in (base, upstream))
        identity.update(GIT_AUTHOR_DATE=f"@{stamp} +0000", GIT_COMMITTER_DATE=f"@{stamp} +0000")
        return self.run("commit-tree", tree, "-p", base, "-p", upstream, "-m",
                        f"Merge upstream release {upstream}", extra_env=identity).stdout.strip()

    def verify(self, base, source):
        # No workflow changes may cross the privileged publication boundary.
        require(not self.value("diff", "--name-only", base, source, "--", ".github/workflows", "scripts/fork-release"),
                "Candidate changes Fork automation. Review and merge those changes manually before retrying.")
        require(not self.value("diff", "--name-only", NATIVE_COMMIT, source, "--", *NATIVE_PATHS),
                "Native inputs changed. Review and update the pinned matching source archive before publishing.")
        require(not self.value("diff", "--name-only", DEPENDENCY_COMMIT, source, "--", *DEPENDENCY_PATHS),
                "Gradle bootstrap changed; review dependencies and rebinding of corresponding source before publishing.")
        # The project uses a single top-level dependencies block; fail closed if its shape changes.
        def dependencies(commit):
            script = self.value("show", f"{commit}:app/build.gradle")
            match = re.search(r"(?m)^dependencies \{\n([\s\S]*?)^\}", script)
            require(match is not None, "Unrecognized app/build.gradle dependencies block.")
            return match.group(1)
        require(dependencies(DEPENDENCY_COMMIT) == dependencies(source),
                "App dependency declarations changed; review corresponding source before publishing.")
        for line in self.value("ls-tree", "-r", source, "--", ".github/workflows", "scripts/fork-release").splitlines():
            require(line.startswith(("100644 ", "100755 ")), "Automation must not contain symlinks or gitlinks.")

    def push(self, plan, token):
        tag = plan["fork_tag"]
        base, source = plan["base"], plan["source"]
        remote_main = self.value("ls-remote", "--refs", "origin", "refs/heads/main").split()
        require(remote_main and remote_main[0] == base,
                "Fork main moved during the run; retry from the new main.")
        current = self.remote_tag(tag)
        require((current or "") == plan["existing_tag"],
                "Fork tag changed during the run; inspect it before retrying.")
        if current is None:
            # Normal atomic push: no force, and neither ref updates if either is rejected.
            require(self.ancestor(base, source), "Candidate would not fast-forward Fork main.")
            header = base64.b64encode(f"x-access-token:{token}".encode()).decode()
            # GitHub masks the raw token, not necessarily its Base64 encoding.
            print(f"::add-mask::{header}", flush=True)
            auth = dict(GIT_CONFIG_COUNT="1", GIT_CONFIG_KEY_0=f"http.https://github.com/{FORK}.git.extraheader",
                        GIT_CONFIG_VALUE_0=f"AUTHORIZATION: basic {header}")
            self.run("push", "--atomic", "origin", f"{source}:refs/heads/main",
                     f"{source}:refs/tags/{tag}", extra_env=auth)
        require(self.remote_tag(tag) == source, "Remote release tag does not point to the tested commit.")


def plan_release(git, requested, approved_workflow, token):
    require(not requested or re.fullmatch("v" + VERSION, requested), "Expected upstream_tag vMAJOR.MINOR.PATCH.")
    endpoint = f"tags/{requested}" if requested else "latest"
    release = api(UPSTREAM, f"releases/{endpoint}", token=token)
    tag = release.get("tag_name")
    require(isinstance(tag, str) and re.fullmatch("v" + VERSION, tag) and (not requested or tag == requested) and
            release.get("draft") is False and release.get("prerelease") is False and release.get("published_at"),
            "Upstream release is not an eligible stable release.")
    version = tag[1:]
    fork_tag = f"revised-{tag}"
    base, tag_object, upstream = git.prepare(tag)
    require(api(UPSTREAM, f"git/ref/tags/{tag}", token=token)["object"]["sha"] == tag_object,
            "Upstream tag changed while being fetched.")
    already = api(FORK, f"releases/tags/{fork_tag}", missing=True, token=token)
    existing = git.remote_tag(fork_tag)
    if existing:
        dest = f"refs/fork-release/{fork_tag}"
        git.run("fetch", "--no-tags", "origin", f"refs/tags/{fork_tag}:{dest}")
        require(git.value("rev-parse", dest) == git.value("rev-parse", dest + "^{commit}") == existing and
                git.ancestor(existing, base) and git.ancestor(upstream, existing),
                "Existing Fork tag is not a lightweight tag for a commit on main containing this upstream tag.")
    if already and already.get("draft") is not True:
        require(already.get("draft") is False and already.get("prerelease") is False and existing,
                "Existing public release is not a valid Fork release.")
        print(f"{fork_tag} is already published; leaving it untouched.")
        return {"needed": False}
    # If main changed the trusted release code since this workflow revision, start a fresh run.
    trusted = Git(Path(__file__).resolve().parents[2])
    for path in (".github/workflows/fork-release.yml", "scripts/fork-release"):
        require(trusted.value("rev-parse", f"{approved_workflow}:{path}") == git.value("rev-parse", f"{base}:{path}"),
                "Release automation changed on main. Start a fresh workflow run.")
    code = version_manifest(UPSTREAM, tag, "xyz.etkn.tv", version)["versionCode"]
    previous_signer = ""
    latest = api(FORK, "releases/latest", missing=True, token=token)
    if latest:
        latest_tag = latest.get("tag_name", "")
        require(re.fullmatch("revised-v" + VERSION, latest_tag), "Unexpected latest Fork release tag.")
        latest_version = latest_tag.removeprefix("revised-v")
        latest_manifest = version_manifest(FORK, latest_tag, PACKAGE, latest_version)
        previous_signer = latest_manifest.get("signerSha256", "")
        require(isinstance(previous_signer, str) and re.fullmatch(r"[a-f0-9]{64}", previous_signer),
                "Latest Fork release has no valid signer fingerprint.")
        require(code > latest_manifest["versionCode"] and tuple(map(int, version.split("."))) > tuple(map(int, latest_version.split("."))),
                "Release version or versionCode would not increase over the current Fork release.")
    source = existing or git.merge(base, upstream)
    git.verify(base, source)
    return dict(needed=True, upstream_tag=tag, upstream=upstream, tag_object=tag_object, base=base,
                source=source, existing_tag=existing or "", version=version, code=code, fork_tag=fork_tag,
                previous_signer_b64=base64.b64encode(bytes.fromhex(previous_signer)).decode("ascii"))


def public_verify(plan, paths, token):
    # REST metadata and the separately verified anonymous downloads can briefly lag publication.
    for attempt in range(6):
        latest = api(FORK, "releases/latest", missing=True, token=token)
        if latest and latest.get("tag_name") == plan["fork_tag"] and latest.get("draft") is False:
            verify_assets(latest, paths)
            live = read_url(f"https://github.com/{FORK}/releases/latest/download/update.json", missing=True)
            if live == paths["update.json"].read_bytes():
                manifest = json.loads(live)
                apk_name = f"etkn-tv-revised-{plan['version']}.apk"
                downloaded = paths["update.json"].parent / "public-check.apk"
                download(asset_url(FORK, plan["fork_tag"], apk_name), downloaded, manifest["sha256"], APK_MAX)
                require(downloaded.stat().st_size == manifest["size"], "Public APK size does not match manifest.")
                return
        if attempt < 5:
            sleep(5)
    raise SystemExit("Could not verify the public latest release and update.json; no publish will be repeated.")


def verify_assets(release, paths):
    assets = release["assets"]
    require(sorted(a["name"] for a in assets) == sorted(paths), "Release asset set does not match.")
    for asset in assets:
        path = paths[asset["name"]]
        require(asset_matches(asset, path), f"GitHub asset verification failed: {path.name}.")


def asset_matches(asset, path):
    return (asset.get("state") == "uploaded" and asset.get("size") == path.stat().st_size and
            asset.get("digest") == f"sha256:{file_digest(path)}")


def publish(git, plan, signed, output, apk_hash, signer, token, already_published=False):
    require(re.fullmatch(r"[a-f0-9]{64}", signer) and
            plan["previous_signer_b64"] in ("", base64.b64encode(bytes.fromhex(signer)).decode("ascii")),
            "Signing certificate differs from the previous public release.")
    require(signed.is_file() and not signed.is_symlink() and re.fullmatch(r"[a-f0-9]{64}", apk_hash) and
            file_digest(signed) == apk_hash and
            100_000 <= signed.stat().st_size <= APK_MAX,
            "Signed APK has an unexpected digest or size.")
    output.mkdir()
    tag, version = plan["fork_tag"], plan["version"]
    apk_name = f"etkn-tv-revised-{version}.apk"
    paths = {apk_name: signed, "update.json": output / "update.json", NATIVE_NAME: output / NATIVE_NAME}
    download(NATIVE_URL, paths[NATIVE_NAME], NATIVE_SHA256, 128 * 1024 * 1024)
    summary = f"ETKN TV Revised {version} (upstream {plan['upstream_tag']})"
    manifest = dict(packageName=PACKAGE, versionName=version, versionCode=plan["code"],
                    size=signed.stat().st_size, apkUrl=asset_url(FORK, tag, apk_name), sha256=apk_hash,
                    signerSha256=signer, releaseNotes=summary)
    paths["update.json"].write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    sums = output / "SHA256SUMS.txt"
    sums.write_text("".join(f"{file_digest(path)}  {name}\n" for name, path in paths.items()), encoding="utf-8")
    paths[sums.name] = sums
    if already_published:
        require(git.remote_tag(tag) == plan["source"], "Published tag does not point to the tested commit.")
        public_verify(plan, paths, token)
        return
    marker = f"<!-- fork-release-v1 tag={tag} source={plan['source']} upstream={plan['upstream']} -->"
    notes = (f"{marker}\n\n{summary}\n\n"
             f"- [Upstream release](https://github.com/{UPSTREAM}/releases/tag/{plan['upstream_tag']})\n"
             f"- [Corresponding application source](https://github.com/{FORK}/tree/{plan['source']}) (GPL-3.0)\n"
             f"- The attached {NATIVE_NAME} contains the corresponding native sources, licenses and rebuild materials.\n")
    # Reuse only our own draft for this source. The public tag endpoint does not reliably return drafts.
    drafts = []
    for page in range(1, 1001):
        batch = api(FORK, f"releases?per_page=100&page={page}", token=token)
        drafts.extend(release for release in batch if release["tag_name"] == tag)
        if len(batch) < 100:
            break
    else:
        raise SystemExit("Could not enumerate existing releases.")
    require(len(drafts) <= 1 and all(d["draft"] and (d.get("body") or "").startswith(marker) for d in drafts),
            "Found a published release or a draft not owned by this workflow; refusing to edit it.")
    if drafts:
        for asset in drafts[0]["assets"]:
            name = asset["name"]
            require(name in paths and asset_matches(asset, paths[name]),
                    "Existing draft asset does not match this run. Re-run failed jobs using the original artifacts, "
                    "or inspect the draft manually. No asset will be replaced.")
    git.push(plan, token)
    draft = (drafts[0] if drafts else api(FORK, "releases", token=token, method="POST",
             payload=dict(tag_name=tag, name=summary, body=notes, draft=True, prerelease=False)))
    release_path = f"releases/{draft['id']}"
    old = {asset["name"]: asset for asset in draft["assets"]}
    for name, path in paths.items():
        if name in old:
            continue
        require(api(FORK, release_path, token=token).get("draft") is True,
                "Release is no longer a draft; refusing to add assets.")
        query = urlencode(dict(name=name))
        upload = f"https://uploads.github.com/repos/{FORK}/{release_path}/assets?{query}"
        read_url(upload, method="POST", data=path.read_bytes(), token=token, content_type="application/octet-stream")
    ready = api(FORK, release_path, token=token)
    require(ready.get("draft") is True and ready.get("prerelease") is False and ready.get("tag_name") == tag and
            ready.get("name") == summary and (ready.get("body") or "").replace("\r\n", "\n").rstrip() == notes.rstrip() and
            git.remote_tag(tag) == plan["source"], "Draft metadata or remote tag does not match the release plan.")
    verify_assets(ready, paths)
    api(FORK, release_path, token=token, method="PATCH", payload=dict(draft=False, make_latest="true"))
    public_verify(plan, paths, token)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["plan", "build", "publish"])
    parser.add_argument("directory", type=Path)
    parser.add_argument("--tag", default="")
    parser.add_argument("--workflow-sha", required=True)
    args = parser.parse_args()
    git = Git(args.directory)
    token = os.environ["GH_TOKEN"]
    plan = plan_release(git, args.tag, args.workflow_sha, token)
    if args.mode == "plan":
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write(f"needed={str(plan['needed']).lower()}\n")
            if plan["needed"]:
                output.write("plan=" + json.dumps(plan, separators=(",", ":")) + "\n")
        return
    expected = json.loads(os.environ["EXPECTED_PLAN"])
    if args.mode == "build":
        require(plan == expected, "Fork or upstream changed; retry with a fresh run.")
        git.run("checkout", "--quiet", "--detach", plan["source"])
        return
    already_published = not plan["needed"]
    if not already_published:
        # When only the publish job is retried, the first attempt may have pushed the tested commit.
        resumed = dict(expected, base=expected["source"], existing_tag=expected["source"])
        require(plan == expected or plan == resumed, "Fork or upstream changed; retry with a fresh run.")
    signer = base64.b64decode(os.environ["SIGNED_CERT_BASE64"], validate=True).hex()
    publish(git, expected if already_published else plan, Path(os.environ["SIGNED_APK"]),
            Path(os.environ["RELEASE_ASSETS"]), os.environ["SIGNED_SHA256"], signer, token,
            already_published=already_published)


if __name__ == "__main__":
    main()
