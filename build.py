#!/usr/bin/env python3
"""
NEON FORGE — dependency-free Android APK build pipeline.

Toolchain (no Gradle / no Android Studio / no Maven):
    aapt2 (linux-x64)              -> compile/link resources + manifest
    ecj   (Eclipse Compiler Java)  -> .java -> .class
    d8    (Android SDK d8.jar)     -> .class -> classes.dex
    python zip writer              -> assemble + 4-byte align stored entries
    apksigner.jar                  -> v1+v2 sign
    keytool                        -> keystore generation

Tools directory (--tools) must contain:
    aapt2, android.jar, ecj.jar, d8.jar, apksigner.jar
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import zlib
import struct

HERE = os.path.dirname(os.path.abspath(__file__))
ZIP_LOCAL = 0x04034b50
ZIP_CENTRAL = 0x02014b50
ZIP_END = 0x06054b50
ALIGN = 4
PAD_EXTRA_ID = 0xd935


def run(cmd):
    print("+ " + " ".join(str(c) for c in cmd))
    r = subprocess.run([str(c) for c in cmd])
    if r.returncode != 0:
        raise SystemExit("command failed: %s" % cmd[0])


# --------------------------------------------------------------------------
# pure-python zip writer (deterministic, aligned stored entries)
# --------------------------------------------------------------------------
def _crc32(data):
    return zlib.crc32(data) & 0xFFFFFFFF


def _make_extra_padding(name_b, extra, data_offset_so_far):
    """Return extra bytes such that data starts at an ALIGN boundary."""
    fixed = 30 + len(name_b) + len(extra)          # local header + name + extra
    pad = (ALIGN - ((data_offset_so_far + fixed) % ALIGN)) % ALIGN
    if pad == 0:
        return extra
    if pad < 4:
        pad += ALIGN
    rec_data = pad - 4
    return extra + struct.pack("<HH", PAD_EXTRA_ID, rec_data) + b"\x00" * rec_data


def write_apk(resources_apk, dex_bytes, assets, out_apk):
    # collect source entries in order
    src_entries = []
    import zipfile
    with zipfile.ZipFile(resources_apk, "r") as z:
        for info in z.infolist():
            if info.filename.endswith("/"):
                continue
            data = z.read(info.filename)
            # keep stored entries stored, else deflate
            comp = zipfile.ZIP_STORED if info.compress_type == zipfile.ZIP_STORED else zipfile.ZIP_DEFLATED
            src_entries.append((info.filename, data, comp))

    # assets appended before classes.dex
    if assets and os.path.isdir(assets):
        for root, _dirs, names in sorted(os.walk(assets)):
            for n in sorted(names):
                p = os.path.join(root, n)
                rel = os.path.relpath(p, assets).replace(os.sep, "/")
                with open(p, "rb") as f:
                    src_entries.append(("assets/" + rel, f.read(), zipfile.ZIP_STORED))

    src_entries.append(("classes.dex", dex_bytes, zipfile.ZIP_STORED))

    out = open(out_apk, "wb")
    central = []

    for name, data, comp in src_entries:
        name_b = name.encode("utf-8")
        crc = _crc32(data)
        if comp == zipfile.ZIP_STORED:
            cdata = data
            method = 0
        else:
            co = zlib.compressobj(9, zlib.DEFLATED, -15)  # raw DEFLATE (zip format)
            cdata = co.compress(data) + co.flush()
            method = 8

        offset = out.tell()
        extra = b""
        if method == 0:
            # align stored data to ALIGN boundary
            extra = _make_extra_padding(name_b, extra, offset)

        lfh = struct.pack("<IHHHHHIIIHH", ZIP_LOCAL, 20, 0x0800, method, 0, 0,
                          crc, len(cdata), len(data), len(name_b), len(extra))
        out.write(lfh)
        out.write(name_b)
        out.write(extra)
        out.write(cdata)

        cen = struct.pack("<IHHHHHHIIIHHHHHII", ZIP_CENTRAL, 20, 20, 0x0800, method,
                          0, 0, crc, len(cdata), len(data), len(name_b), len(extra),
                          0, 0, 0, 0, offset)
        central.append((cen, name_b, extra))

    cd_start = out.tell()
    for cen, name_b, extra in central:
        out.write(cen)
        out.write(name_b)
        out.write(extra)

    cd_size = out.tell() - cd_start
    out.write(struct.pack("<IHHHHIIH", ZIP_END, 0, 0, len(central), len(central),
                          cd_size, cd_start, 0))
    out.close()


# --------------------------------------------------------------------------
# steps
# --------------------------------------------------------------------------
def aapt2_compile(tools, res_dir, out):
    run([os.path.join(tools, "aapt2"), "compile", "--dir", res_dir, "-o", out])


def aapt2_link(tools, manifest, compiled, out_apk, min_sdk, target_sdk, version_code,
               version_name, assets):
    cmd = [os.path.join(tools, "aapt2"), "link", "-o", out_apk,
           "-I", os.path.join(tools, "android.jar"),
           "--manifest", manifest,
           "--min-sdk-version", str(min_sdk),
           "--target-sdk-version", str(target_sdk),
           "--version-code", str(version_code),
           "--version-name", version_name,
           "--auto-add-overlay"]
    if assets and os.path.isdir(assets) and os.listdir(assets):
        cmd += ["-A", assets]
    if compiled:
        cmd.append(compiled)
    run(cmd)


def ecj_compile(tools, src_dirs, out_classes, java):
    files = []
    for d in src_dirs:
        for root, _dirs, names in os.walk(d):
            for n in names:
                if n.endswith(".java"):
                    files.append(os.path.join(root, n))
    if not files:
        raise SystemExit("no .java sources under: %s" % src_dirs)
    os.makedirs(out_classes, exist_ok=True)
    android_jar = os.path.join(tools, "android.jar")
    run([java, "-jar", os.path.join(tools, "ecj.jar"),
         "-source", "8", "-target", "8", "-encoding", "UTF-8", "-nowarn",
         "-bootclasspath", android_jar, "-classpath", android_jar,
         "-d", out_classes] + files)


def d8_dex(tools, in_classes, out_dir, min_sdk, java):
    os.makedirs(out_dir, exist_ok=True)
    classfiles = []
    for root, _dirs, names in os.walk(in_classes):
        for n in names:
            if n.endswith(".class"):
                classfiles.append(os.path.join(root, n))
    if not classfiles:
        raise SystemExit("no .class files under: %s" % in_classes)
    android_jar = os.path.join(tools, "android.jar")
    d8 = os.path.join(tools, "d8.jar")
    for i in range(0, len(classfiles), 400):
        run([java, "-cp", d8, "com.android.tools.r8.D8",
             "--release", "--min-api", str(min_sdk),
             "--lib", android_jar, "--output", out_dir] + classfiles[i:i + 400])


def keytool_genkeystore(java_home, keystore, alias, storepass, dname):
    if os.path.exists(keystore):
        return
    run([os.path.join(java_home, "bin", "keytool"), "-genkeypair",
         "-alias", alias, "-keyalg", "RSA", "-keysize", "2048",
         "-sigalg", "SHA256withRSA", "-validity", "10000",
         "-keystore", keystore, "-storetype", "PKCS12",
         "-storepass", storepass, "-keypass", storepass, "-dname", dname])


def sign(tools, unsigned, out_apk, keystore, alias, storepass, java):
    run([java, "-jar", os.path.join(tools, "apksigner.jar"), "sign",
         "--ks", keystore, "--ks-key-alias", alias,
         "--ks-pass", "pass:" + storepass, "--key-pass", "pass:" + storepass,
         "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
         "--v3-signing-enabled", "false",
         "--out", out_apk, unsigned])


def verify(tools, apk, java):
    run([java, "-jar", os.path.join(tools, "apksigner.jar"),
         "verify", "--verbose", "--print-certs", apk])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tools", default=os.path.join(HERE, "bin"))
    ap.add_argument("--java", default="java")
    ap.add_argument("--src", action="append", required=True)
    ap.add_argument("--res", default=None)
    ap.add_argument("--assets", default=None)
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--min-sdk", type=int, default=26)
    ap.add_argument("--target-sdk", type=int, default=34)
    ap.add_argument("--version-code", type=int, default=1)
    ap.add_argument("--version-name", default="1.0")
    ap.add_argument("--keystore", default=None)
    ap.add_argument("--ks-alias", default="neonforge")
    ap.add_argument("--ks-pass", default="neonforge")
    ap.add_argument("--ks-dname", default="CN=NEON FORGE,O=NeonForge,C=BR")
    ap.add_argument("--sign", action="store_true")
    ap.add_argument("--work", default=None)
    args = ap.parse_args()

    work = args.work or tempfile.mkdtemp(prefix="nfbuild-")
    os.makedirs(work, exist_ok=True)
    res_compiled = os.path.join(work, "res.zip")
    resources_apk = os.path.join(work, "resources.apk")
    classes_dir = os.path.join(work, "classes")
    dex_dir = os.path.join(work, "dex")
    unsigned = os.path.join(work, "unsigned.apk")

    if args.res and os.path.isdir(args.res) and os.listdir(args.res):
        aapt2_compile(args.tools, args.res, res_compiled)
        aapt2_link(args.tools, args.manifest, res_compiled, resources_apk,
                   args.min_sdk, args.target_sdk, args.version_code, args.version_name,
                   args.assets)
    else:
        aapt2_link(args.tools, args.manifest, None, resources_apk,
                   args.min_sdk, args.target_sdk, args.version_code, args.version_name,
                   args.assets)

    ecj_compile(args.tools, args.src, classes_dir, args.java)
    d8_dex(args.tools, classes_dir, dex_dir, args.min_sdk, args.java)

    with open(os.path.join(dex_dir, "classes.dex"), "rb") as f:
        dex_bytes = f.read()
    write_apk(resources_apk, dex_bytes, args.assets, unsigned)

    out_apk = os.path.abspath(args.out)
    os.makedirs(os.path.dirname(out_apk), exist_ok=True)

    if not args.sign:
        shutil.copyfile(unsigned, out_apk)
        print("Unsigned APK written to %s" % out_apk)
        return

    ks = args.keystore or os.path.join(work, "release.keystore")
    if not os.path.exists(ks):
        java_bin = shutil.which(args.java) or os.path.join(HERE, "java")
        java_home = os.path.dirname(os.path.dirname(os.path.realpath(java_bin)))
        keytool_genkeystore(java_home, ks, args.ks_alias, args.ks_pass, args.ks_dname)

    sign(args.tools, unsigned, out_apk, ks, args.ks_alias, args.ks_pass, args.java)
    verify(args.tools, out_apk, args.java)
    print("APK built and verified: %s (%d bytes)" % (out_apk, os.path.getsize(out_apk)))


if __name__ == "__main__":
    main()
