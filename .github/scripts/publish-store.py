"""Writes the runtime's update store (the `repo` branch) from a release build.

    publish-store.py --apk host/build/outputs/apk/release/<apk> \
        --info host/build/outputs/runtime-info.json --out repo \
        --release-url https://github.com/keiyoushi/runtime/releases/download/v<versionCode>

Output, all in --out (anything else there is removed, except .git):
- index.pb (gzipped) and index.json: the v2 index, read by Mihon and forks that have caught up with it
- repo.json: the legacy store metadata; its `index_v2` sends v2 apps on to index.pb
- index.min.json: the legacy extension list, the URL users add (apps without v2 read only this)
- apk/<apk>: legacy apps download from <store>/apk/<apk>; v2 apps take the GitHub Release asset
- icon/<package>.png
"""

import argparse
import gzip
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import index_pb2  # noqa: E402
from google.protobuf import json_format  # noqa: E402

STORE_NAME = "Keiyoushi Runtime"
BADGE = "KEI RT"
WEBSITE = "https://github.com/keiyoushi/runtime"
DISCORD = "https://discord.gg/3FbCpdKbdY"
DEFAULT_BASE_URL = "https://raw.githubusercontent.com/keiyoushi/runtime/repo"


def build_tool(name: str, build_tools: str | None) -> str:
    if build_tools:
        return str(Path(build_tools) / name)
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        sys.exit(f"{name}: set ANDROID_HOME or pass --build-tools")
    versions = sorted(
        (Path(sdk) / "build-tools").iterdir(),
        key=lambda p: [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", p.name)],
    )
    return str(versions[-1] / name)


def signing_fingerprint(apk: Path, build_tools: str | None) -> str:
    out = subprocess.run(
        [build_tool("apksigner", build_tools), "verify", "--print-certs", str(apk)],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    digests = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$", out, re.M)
    if len(digests) != 1:
        sys.exit(f"{apk.name}: expected one signer, found {len(digests)}")
    return digests[0]


def check_badging(apk: Path, info: dict, build_tools: str | None) -> None:
    out = subprocess.run(
        [build_tool("aapt2", build_tools), "dump", "badging", str(apk)],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", out, re.M)
    found = (package.group(1), int(package.group(2)), package.group(3)) if package else None
    expected = (info["packageName"], info["versionCode"], info["versionName"])
    if found != expected:
        sys.exit(f"{apk.name} is {found}, runtime-info.json says {expected}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--info", type=Path, required=True, help="host/build/outputs/runtime-info.json")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--release-url", required=True, help="where the release's assets download from")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help="where --out is served")
    parser.add_argument("--icon", type=Path, default=Path("host/res/mipmap-xhdpi/ic_launcher.png"))
    parser.add_argument("--fingerprint", help="the APK's SHA-256 cert digest; read with apksigner when unset")
    parser.add_argument("--expect-fingerprint", type=Path, help="file holding the digest the APK must be signed with")
    parser.add_argument("--build-tools", help="Android build-tools dir (default: newest under ANDROID_HOME)")
    args = parser.parse_args()

    info = json.loads(args.info.read_text(encoding="utf-8"))
    base_url = args.base_url.rstrip("/")
    package = info["packageName"]
    source = info["source"]

    fingerprint = (args.fingerprint or signing_fingerprint(args.apk, args.build_tools)).lower()
    if args.expect_fingerprint:
        expected = args.expect_fingerprint.read_text(encoding="utf-8").strip().lower()
        if fingerprint != expected:
            sys.exit(f"{args.apk.name} is signed with {fingerprint}, not the store key {expected}")
    check_badging(args.apk, info, args.build_tools)

    out = args.out
    out.mkdir(parents=True, exist_ok=True)
    for child in out.iterdir():
        if child.name == ".git":
            continue
        shutil.rmtree(child) if child.is_dir() else child.unlink()

    (out / "apk").mkdir()
    shutil.copyfile(args.apk, out / "apk" / args.apk.name)
    (out / "icon").mkdir()
    shutil.copyfile(args.icon, out / "icon" / f"{package}.png")
    icon_url = f"{base_url}/icon/{package}.png"

    extension = index_pb2.Extension(
        name=info["name"],
        packageName=package,
        resources=index_pb2.Resources(
            apkUrl=f"{args.release_url.rstrip('/')}/{args.apk.name}",
            iconUrl=icon_url,
        ),
        extensionLib=info["extensionLib"],
        versionCode=info["versionCode"],
        versionName=info["versionName"],
        contentWarning=index_pb2.CONTENT_WARNING_SAFE,
        # The apps offer an extension for install by its sources' languages: one `all` row
        sources=[
            index_pb2.Source(
                id=int(source["id"]),
                name=source["name"],
                language=source["lang"],
                homeUrl=WEBSITE,
            )
        ],
        runtimeProvides={name: module["api"] for name, module in info["modules"].items()},
    )
    index = index_pb2.Index(
        name=STORE_NAME,
        badgeLabel=BADGE,
        signingKey=fingerprint,
        contact=index_pb2.Contact(website=WEBSITE, discord=DISCORD),
        extensionList=index_pb2.ExtensionList(extensions=[extension]),
    )

    (out / "index.json").write_text(
        json_format.MessageToJson(
            index,
            always_print_fields_with_no_presence=False,
            preserving_proto_field_name=True,
        )
        + "\n",
        encoding="utf-8",
    )
    (out / "index.pb").write_bytes(gzip.compress(index.SerializeToString(deterministic=True), mtime=0))

    # Mihon: data/.../NetworkLegacyExtensionRepo (`index_v2`, `meta`)
    repo = {
        "meta": {
            "name": STORE_NAME,
            "shortName": BADGE,
            "website": WEBSITE,
            "signingKeyFingerprint": fingerprint,
        },
        "index_v2": f"{base_url}/index.pb",
    }
    (out / "repo.json").write_text(json.dumps(repo, indent=2) + "\n", encoding="utf-8")

    # Mihon: data/.../NetworkLegacyExtension; the apps read lib 1.6 from "version" (1.6.<code>)
    legacy = [
        {
            "name": f"Tachiyomi: {info['name']}",
            "pkg": package,
            "apk": args.apk.name,
            "lang": source["lang"],
            "code": info["versionCode"],
            "version": info["versionName"],
            "nsfw": 0,
            "sources": [
                {
                    "id": int(source["id"]),
                    "lang": source["lang"],
                    "name": source["name"],
                    "baseUrl": WEBSITE,
                }
            ],
        }
    ]
    (out / "index.min.json").write_text(json.dumps(legacy, separators=(",", ":")) + "\n", encoding="utf-8")

    print(f"{package} {info['versionName']} ({info['versionCode']}), key {fingerprint}: {base_url}/index.min.json")


if __name__ == "__main__":
    main()
