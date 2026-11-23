#!/usr/bin/env python3
"""Bake Alpine packages into the Android sandbox rootfs at build time.

    android_rootfs_add_packages.py --base alpine-minirootfs-X-aarch64.tar.gz \
        --out alpine-minirootfs.tar.gz --cache DIR git ripgrep

Why this exists
---------------
The app ships a stock Alpine minirootfs and extracts it on first launch
(RootfsManager.installIfNeeded). Anything not in it has to be `apk add`ed by the
agent at run time, which costs a network round trip on the first coding task
and fails outright when the device is offline. A coding agent needs `git` and
`rg` from the first command, so they are installed here, once, on the build
machine.

Why not `apk add` in a container
--------------------------------
The rootfs is aarch64 and the build machine is not, so the real `apk` cannot
run its target binaries; and the build must also work on a developer's Mac.
This script therefore does what `apk add` does, with nothing but the Python
standard library:

  1. read APKINDEX for `main` and `community`;
  2. resolve the requested packages' dependencies against what the minirootfs
     already has installed;
  3. download each .apk and verify it against the index (see `verify_apk`);
  4. append the package files to the rootfs tarball;
  5. register the packages in `lib/apk/db/installed` and `etc/apk/world`, so
     the on-device `apk` knows they are there: `apk add git` is a no-op,
     `apk upgrade` upgrades them, and `apk add <other>` does not purge them as
     orphans.

Two things are shaped by the app's own tar reader (RootfsManager.extractTar),
which is deliberately minimal:

  * it understands plain ustar headers only (name + prefix, no pax/GNU long
    names), so the output is written in USTAR format and the script fails if a
    path does not fit;
  * it materialises a hard link as a full COPY of its target. `git` ships its
    built-ins as ~150 hard links to one 3 MB binary, which would extract to
    hundreds of megabytes on the phone — so hard links are rewritten as
    relative symlinks.

Package install scripts are NOT run (they are aarch64 shell scripts meant for a
live system). The script refuses to continue if a selected package carries a
pre/post-install script that is not explicitly waved through with
`--skip-scripts-of`, so a package that needs one cannot slip in silently.
"""

from __future__ import annotations

import argparse
import base64
import gzip
import hashlib
import io
import os
import posixpath
import re
import sys
import tarfile
import urllib.request
import zlib

MIRROR = "https://dl-cdn.alpinelinux.org/alpine"
REPOS = ("main", "community")
INSTALL_SCRIPTS = (".pre-install", ".post-install")


def log(msg: str) -> None:
    print(f"[rootfs-packages] {msg}", flush=True)


def die(msg: str) -> "NoReturn":  # noqa: F821
    print(f"[rootfs-packages] ERROR: {msg}", file=sys.stderr, flush=True)
    sys.exit(1)


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "openminis-rootfs-build"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        return resp.read()


# --------------------------------------------------------------------- index

def parse_index_text(text: str) -> list[dict]:
    """APKINDEX / installed-db text -> one dict per package.

    Each dict keeps the single-letter fields and, under "_lines", the raw lines
    of the block in order (so an index block can be copied verbatim into the
    installed db, which uses the same field letters).
    """
    packages = []
    for block in text.split("\n\n"):
        fields: dict = {"_lines": []}
        for line in block.split("\n"):
            if len(line) < 2 or line[1] != ":":
                continue
            fields["_lines"].append(line)
            fields.setdefault(line[0], line[2:])
        if "P" in fields:
            packages.append(fields)
    return packages


def dep_name(token: str) -> str:
    """`so:libcurl.so.4`, `musl>=1.2`, `pc:zlib=1.3` -> the bare name."""
    return re.split(r"[<>=~]", token, maxsplit=1)[0]


def provided_names(pkg: dict) -> set[str]:
    names = {pkg["P"]}
    for token in pkg.get("p", "").split():
        names.add(dep_name(token))
    return names


def load_index(version: str, arch: str) -> tuple[dict, dict]:
    """-> (by_name, providers). `main` wins over `community` on a name clash."""
    by_name: dict[str, dict] = {}
    providers: dict[str, list[dict]] = {}
    for repo in REPOS:
        url = f"{MIRROR}/v{version}/{repo}/{arch}/APKINDEX.tar.gz"
        log(f"index: {url}")
        raw = fetch(url)
        # APKINDEX.tar.gz is two concatenated gzip streams (signature, index);
        # tarfile reads through both.
        with tarfile.open(fileobj=io.BytesIO(raw), mode="r:gz") as tar:
            text = tar.extractfile("APKINDEX").read().decode("utf-8")
        for pkg in parse_index_text(text):
            pkg["_repo"] = repo
            if pkg["P"] in by_name:
                continue
            by_name[pkg["P"]] = pkg
            for name in provided_names(pkg):
                providers.setdefault(name, []).append(pkg)
    return by_name, providers


def resolve(wanted: list[str], by_name: dict, providers: dict, installed: list[dict]) -> list[dict]:
    """The packages to add, dependencies included, in a stable order."""
    have: set[str] = set()
    for pkg in installed:
        have |= provided_names(pkg)

    selected: dict[str, dict] = {}
    queue = list(wanted)
    while queue:
        while queue:
            token = queue.pop(0)
            if token.startswith("!"):  # a conflict, not a requirement
                continue
            name = dep_name(token)
            # An absolute path dependency (`/bin/sh`) is a file some installed
            # package owns; the minirootfs has busybox, which is what these mean.
            if name.startswith("/") or name in have:
                continue
            pkg = by_name.get(name)
            if pkg is None:
                candidates = providers.get(name, [])
                if not candidates:
                    die(f"nothing provides '{name}' (needed while resolving {wanted})")
                # Highest declared provider priority (k:) first, then name
                # order so the choice is deterministic.
                candidates = sorted(candidates, key=lambda p: (-int(p.get("k", "0") or 0), p["P"]))
                pkg = candidates[0]
            selected[pkg["P"]] = pkg
            have |= provided_names(pkg)
            queue.extend(pkg.get("D", "").split())
        # `install_if` (i:): a package apk pulls in by itself once everything it
        # names is installed (git -> git-init-template). Without this the first
        # `apk add` on the device would fetch it, so "nothing left to install"
        # would not hold. Checked once the queue drains, then drained again.
        for other in sorted(by_name.values(), key=lambda p: p["P"]):
            rule = other.get("i", "").split()
            if not rule or other["P"] in selected or other["P"] in have:
                continue
            if all(install_if_met(tok, have, selected, installed) for tok in rule):
                queue.append(other["P"])
    return [selected[k] for k in sorted(selected)]


def install_if_met(token: str, have: set, selected: dict, installed: list[dict]) -> bool:
    name = dep_name(token)
    if name not in have:
        return False
    if "=" not in token or any(op in token for op in ("<", ">", "~")):
        return True
    version = token.split("=", 1)[1]
    pkg = selected.get(name) or next((p for p in installed if p["P"] == name), None)
    return pkg is not None and pkg.get("V") == version


# ----------------------------------------------------------------------- apk

def split_gzip_streams(blob: bytes) -> list[tuple[bytes, bytes]]:
    """A .apk is concatenated gzip streams. -> [(compressed, decompressed), ...]"""
    streams = []
    rest = blob
    while rest:
        d = zlib.decompressobj(31)
        plain = d.decompress(rest) + d.flush()
        if not d.eof:
            die("truncated gzip stream inside an .apk")
        used = len(rest) - len(d.unused_data)
        streams.append((rest[:used], plain))
        rest = d.unused_data
    return streams


def q1(digest: bytes) -> str:
    return "Q1" + base64.b64encode(digest).decode("ascii")


def verify_apk(blob: bytes, pkg: dict) -> tuple[bytes, list[str]]:
    """Check the download against the index; -> (data tar bytes, script names).

    The chain is the one apk itself uses: the index's `C:` is the SHA-1 of the
    package's control segment, and the control segment's `.PKGINFO` carries
    `datahash`, the SHA-256 of the data segment. So a package that matches the
    index cannot have had its files swapped. (The index is trusted as fetched
    over HTTPS, the same trust the minirootfs download already rests on.)
    """
    streams = split_gzip_streams(blob)
    if len(streams) < 2:
        die(f"{pkg['P']}: unexpected .apk layout ({len(streams)} segments)")
    control_gz, control_tar = streams[-2]
    data_gz, data_tar = streams[-1]

    if q1(hashlib.sha1(control_gz).digest()) != pkg["C"]:
        die(f"{pkg['P']}: control segment does not match the index checksum")

    datahash = None
    scripts: list[str] = []
    with tarfile.open(fileobj=io.BytesIO(control_tar), mode="r:") as tar:
        for member in tar:
            if member.name == ".PKGINFO":
                for line in tar.extractfile(member).read().decode("utf-8").splitlines():
                    if line.startswith("datahash = "):
                        datahash = line.split(" = ", 1)[1].strip()
            elif member.name in INSTALL_SCRIPTS:
                scripts.append(member.name)
    if datahash is None:
        die(f"{pkg['P']}: .PKGINFO has no datahash")
    if hashlib.sha256(data_gz).hexdigest() != datahash:
        die(f"{pkg['P']}: data segment does not match .PKGINFO datahash")
    return data_tar, scripts


def download_apk(pkg: dict, version: str, arch: str, cache: str) -> bytes:
    filename = f"{pkg['P']}-{pkg['V']}.apk"
    path = os.path.join(cache, filename)
    if os.path.isfile(path):
        with open(path, "rb") as fh:
            return fh.read()
    url = f"{MIRROR}/v{version}/{pkg['_repo']}/{arch}/{filename}"
    log(f"download: {filename}")
    blob = fetch(url)
    tmp = path + ".part"
    with open(tmp, "wb") as fh:
        fh.write(blob)
    os.replace(tmp, path)
    return blob


# -------------------------------------------------------------------- output

def norm(name: str) -> str:
    """Tar member name -> rootfs-relative path without `./` or trailing slash."""
    name = name.lstrip("./") if name.startswith("./") else name
    return name.strip("/")


def ustar_info(path: str, src: tarfile.TarInfo) -> tarfile.TarInfo:
    info = tarfile.TarInfo("./" + path)
    info.mode = src.mode
    info.mtime = src.mtime
    info.uid = info.gid = 0
    info.uname = info.gname = "root"
    info.type = src.type
    info.linkname = src.linkname
    info.size = src.size if src.isreg() else 0
    return info


def db_file_lines(entries: list[dict]) -> list[str]:
    """`F:/R:/a:/Z:` lines for one package, in the installed-db layout."""
    dirs: dict[str, dict] = {}
    for e in entries:
        if e["kind"] == "dir":
            dirs.setdefault(e["path"], {"mode": e["mode"], "files": []})
    for e in entries:
        if e["kind"] == "dir":
            continue
        parent = posixpath.dirname(e["path"])
        dirs.setdefault(parent, {"mode": 0o755, "files": []})["files"].append(e)

    lines: list[str] = []
    for d in sorted(dirs):
        lines.append(f"F:{d}")
        if dirs[d]["mode"] != 0o755:
            lines.append(f"M:0:0:{dirs[d]['mode']:o}")
        for e in sorted(dirs[d]["files"], key=lambda x: x["path"]):
            lines.append(f"R:{posixpath.basename(e['path'])}")
            if e["mode"] != 0o644:
                lines.append(f"a:0:0:{e['mode']:o}")
            lines.append(f"Z:{q1(e['sha1'])}")
    return lines


def build(args: argparse.Namespace) -> None:
    os.makedirs(args.cache, exist_ok=True)

    with tarfile.open(args.base, mode="r:gz") as base:
        installed_text = base.extractfile("./lib/apk/db/installed").read().decode("utf-8")
        world_text = base.extractfile("./etc/apk/world").read().decode("utf-8")
    installed = parse_index_text(installed_text)
    installed_names = {p["P"] for p in installed}

    by_name, providers = load_index(args.alpine_version, args.arch)
    for name in args.packages:
        if name not in by_name:
            die(f"package '{name}' is not in Alpine v{args.alpine_version} {args.arch} main/community")
    to_add = [p for p in resolve(args.packages, by_name, providers, installed) if p["P"] not in installed_names]
    log("adding: " + " ".join(f"{p['P']}-{p['V']}" for p in to_add))

    waved = set(args.skip_scripts_of or [])
    payloads: list[tuple[dict, bytes]] = []
    for pkg in to_add:
        data_tar, scripts = verify_apk(download_apk(pkg, args.alpine_version, args.arch, args.cache), pkg)
        if scripts and pkg["P"] not in waved:
            die(
                f"{pkg['P']} ships {', '.join(scripts)}, which this build cannot run. "
                f"Read the script; if the rootfs works without it, pass --skip-scripts-of {pkg['P']}."
            )
        if scripts:
            log(f"note: not running {', '.join(scripts)} of {pkg['P']} (waved through)")
        payloads.append((pkg, data_tar))

    out_buf = io.BytesIO()
    present: dict[str, str] = {}  # path -> kind
    db_blocks: list[str] = []
    added_bytes = 0

    with tarfile.open(fileobj=out_buf, mode="w", format=tarfile.USTAR_FORMAT) as out:
        # 1. the minirootfs, minus the two apk files rewritten at the end
        with tarfile.open(args.base, mode="r:gz") as base:
            for member in base:
                path = norm(member.name)
                if path in ("lib/apk/db/installed", "etc/apk/world"):
                    continue
                if path == "":
                    continue
                info = ustar_info(path, member)
                present[path] = "dir" if member.isdir() else "file"
                out.addfile(info, base.extractfile(member) if member.isreg() else None)

        # 2. each package's files
        for pkg, data_tar in payloads:
            entries: list[dict] = []
            with tarfile.open(fileobj=io.BytesIO(data_tar), mode="r:") as tar:
                members = tar.getmembers()
                by_path = {norm(m.name): m for m in members}
                for member in members:
                    path = norm(member.name)
                    if path == "" or path.startswith("."):
                        continue
                    if member.isdir():
                        entries.append({"kind": "dir", "path": path, "mode": member.mode & 0o7777})
                        if path not in present:
                            present[path] = "dir"
                            out.addfile(ustar_info(path, member))
                        continue
                    if present.get(path) == "file":
                        die(f"{pkg['P']}: {path} already exists in the rootfs")
                    present[path] = "file"

                    if member.isreg():
                        content = tar.extractfile(member).read()
                        added_bytes += len(content)
                        out.addfile(ustar_info(path, member), io.BytesIO(content))
                        entries.append({"kind": "file", "path": path, "mode": member.mode & 0o7777,
                                        "sha1": hashlib.sha1(content).digest()})
                    elif member.issym():
                        out.addfile(ustar_info(path, member))
                        entries.append({"kind": "file", "path": path, "mode": 0o777,
                                        "sha1": hashlib.sha1(member.linkname.encode()).digest()})
                    elif member.islnk():
                        # Hard link -> relative symlink (see module docstring).
                        target = norm(member.linkname)
                        target_member = by_path.get(target)
                        if target_member is None or not target_member.isreg():
                            die(f"{pkg['P']}: hard link {path} -> {target} has no regular-file target")
                        rel = posixpath.relpath(target, posixpath.dirname(path) or ".")
                        info = ustar_info(path, member)
                        info.type = tarfile.SYMTYPE
                        info.linkname = rel
                        info.mode = 0o777
                        out.addfile(info)
                        entries.append({"kind": "file", "path": path, "mode": 0o777,
                                        "sha1": hashlib.sha1(rel.encode()).digest()})
                    else:
                        die(f"{pkg['P']}: unsupported entry type for {path}")
            db_blocks.append("\n".join(pkg["_lines"] + db_file_lines(entries)))

        # 3. the apk database and world file
        new_installed = installed_text.rstrip("\n") + "\n\n" + "\n\n".join(db_blocks) + "\n\n"
        world = [w for w in world_text.split("\n") if w]
        for name in args.packages:
            if name not in world:
                world.append(name)
        for path, text, mode in (
            ("lib/apk/db/installed", new_installed, 0o644),
            ("etc/apk/world", "\n".join(world) + "\n", 0o644),
        ):
            blob = text.encode("utf-8")
            info = tarfile.TarInfo("./" + path)
            info.mode = mode
            info.size = len(blob)
            info.uname = info.gname = "root"
            out.addfile(info, io.BytesIO(blob))

    tmp = args.out + ".part"
    with open(tmp, "wb") as raw:
        # mtime=0 and no filename: the same inputs give the same bytes.
        with gzip.GzipFile(filename="", fileobj=raw, mode="wb", compresslevel=9, mtime=0) as gz:
            gz.write(out_buf.getvalue())
    os.replace(tmp, args.out)
    log(f"wrote {args.out}: {os.path.getsize(args.out) / 1e6:.1f} MB compressed, "
        f"+{added_bytes / 1e6:.1f} MB of package files, {len(to_add)} package(s)")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--base", required=True, help="stock alpine-minirootfs .tar.gz")
    ap.add_argument("--out", required=True, help="rootfs .tar.gz to write")
    ap.add_argument("--cache", required=True, help="directory for downloaded .apk files")
    ap.add_argument("--alpine-version", default="3.21", help="release branch, e.g. 3.21")
    ap.add_argument("--arch", default="aarch64")
    ap.add_argument("--skip-scripts-of", action="append", metavar="PKG",
                    help="accept that PKG's install script is not run (repeatable)")
    ap.add_argument("packages", nargs="+")
    build(ap.parse_args())


if __name__ == "__main__":
    main()
