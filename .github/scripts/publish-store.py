"""Writes the runtime's update store (the `repo` branch) from a release build.

    publish-store.py --apk host/build/outputs/apk/release/<apk> \
        --info host/build/outputs/runtime-info.json --out repo \
        --release-url https://github.com/keiyoushi/runtime/releases/download/v<versionCode>

Output, all in --out (anything else there is removed, except .git), as keiyoushi's publish-repo.py
writes its store:
- index.pb (gzipped): the v2 index Mihon reads; users add <repo branch>/index.pb
- index.json: the same index as protobuf JSON, for people
The APK downloads from the GitHub Release (apkUrl); the icon from the tagged source tree (iconUrl).
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
DEFAULT_ICON_URL = "https://cdn.jsdelivr.net/gh/keiyoushi/runtime@main/host/res/mipmap-xxxhdpi/ic_launcher.png"
# The scaffold's stand-in key (CN=Keiyoushi Runtime TEMP). The release key is permanent: a runtime
# signed with this one could never update to the org key without every user reinstalling
TEMP_KEY_FINGERPRINT = "2216820f57f3d299f59e50420b999b753633d214931448b6b2f90dd5af0935a1"
INSTALL_ORG_KEY = (
    "install the keiyoushi org key: the four signing secrets and .github/signing-key-fingerprint (CONTRIBUTING.md, Signing)"
)


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


def signing_cert(apk: Path, build_tools: str | None) -> tuple[str, str]:
    """The single signer's SHA-256 digest and subject DN"""
    out = subprocess.run(
        [build_tool("apksigner", build_tools), "verify", "--print-certs", str(apk)],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    # "Signer #1 certificate ..." or per-scheme "V3.0 Signer: certificate ..." depending on build-tools
    digests = set(re.findall(r"^.*Signer\b.*certificate SHA-256 digest: ([0-9a-f]{64})$", out, re.M))
    subjects = set(re.findall(r"^.*Signer\b.*certificate DN: (.*)$", out, re.M))
    if len(digests) != 1 or len(subjects) != 1:
        sys.exit(f"{apk.name}: expected one signer, found {len(digests)}")
    return digests.pop(), subjects.pop()


def refuse_temp_key(fingerprint: str, subject: str | None) -> None:
    if fingerprint == TEMP_KEY_FINGERPRINT or (subject and re.search(r"\bTEMP\b", subject, re.I)):
        sys.exit(f"Refusing to publish: the APK is signed with the temporary key ({subject or fingerprint}); {INSTALL_ORG_KEY}")


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
    parser.add_argument("--icon-url", default=DEFAULT_ICON_URL, help="the launcher icon in the source tree")
    parser.add_argument("--fingerprint", help="the APK's SHA-256 cert digest; read with apksigner when unset")
    parser.add_argument("--expect-fingerprint", type=Path, help="file holding the digest the APK must be signed with")
    parser.add_argument("--build-tools", help="Android build-tools dir (default: newest under ANDROID_HOME)")
    args = parser.parse_args()

    info = json.loads(args.info.read_text(encoding="utf-8"))
    package = info["packageName"]
    source = info["source"]

    if args.fingerprint:
        fingerprint, subject = args.fingerprint.lower(), None
    else:
        fingerprint, subject = signing_cert(args.apk, args.build_tools)
    refuse_temp_key(fingerprint, subject)
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

    extension = index_pb2.Extension(
        name=info["name"],
        packageName=package,
        resources=index_pb2.Resources(
            apkUrl=f"{args.release_url.rstrip('/')}/{args.apk.name}",
            iconUrl=args.icon_url,
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
    )
    # Mihon: data/.../NetworkExtensionStore `signingKey` (field 3). Adding the store trusts APKs
    # signed with it (TrustExtension.isTrusted), so the runtime installs without a trust prompt
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

    print(f"{package} {info['versionName']} ({info['versionCode']}), key {fingerprint}: {extension.resources.apkUrl}")


if __name__ == "__main__":
    main()
