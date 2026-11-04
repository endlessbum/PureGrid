#!/usr/bin/env python3
"""Fallback file transfer: send a file to the TV as base64 chunks through plain shell commands.

Usage: b64push.py <local file> <remote path>

In Git Bash quote the remote path yourself and set MSYS_NO_PATHCONV=1 if it starts with a
slash, or MSYS will rewrite it into a Windows path before this script ever sees it.
"""
import base64
import hashlib
import os
import shlex
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tv  # noqa: E402

CHUNK = 48 * 1024  # raw bytes per shell command (base64 grows it by 4/3); capped per TV in main()
# adbd before Android 7 aborts the connection on an ADB "open" payload over 4096 bytes, which
# Android 5.x TVs announce as their max data size. Newer adbd takes the full 48 KB.


def main():
    if len(sys.argv) != 3:
        sys.exit("usage: b64push.py <local file> <remote path>")
    local, remote = sys.argv[1], sys.argv[2]
    with open(local, "rb") as f:
        data = f.read()
    want = hashlib.md5(data).hexdigest()
    dev = tv.connect()
    chunk = min(CHUNK, dev.max_chunk_size * 3 // 4 - 64)   # base64 of the chunk plus the echo must fit one payload
    chunk -= chunk % 3   # a multiple of 3, or each chunk ends in '=' padding mid-stream and base64 -d corrupts it
    tmp = remote + ".b64"
    # The remote path is caller-supplied and lands in device shell commands: quote it like any
    # other interpolated value, or a path with a space breaks the transfer and a path with a
    # shell metacharacter becomes an arbitrary command on the TV.
    r = shlex.quote(remote)
    t = shlex.quote(tmp)
    dev.shell(f"rm -f {r} {t}")
    n = (len(data) + chunk - 1) // chunk
    for i in range(n):
        part = base64.b64encode(data[i * chunk:(i + 1) * chunk]).decode()
        out = dev.shell(f"echo '{part}' >> {t} && echo ok{i}", read_timeout_s=60.0)
        if f"ok{i}" not in out:
            print(f"chunk {i} failed: {out}")
            dev.shell(f"rm -f {t}")   # do not leave the partial stream on the TV
            dev.close()
            sys.exit(1)
        print(f"\rchunk {i + 1}/{n}", end="", flush=True)
    print()
    out = dev.shell(f"base64 -d {t} > {r} && rm {t} && md5sum {r}", read_timeout_s=120.0)
    got = out.split()[0] if out.strip() else ""
    print("md5 remote:", got, "local:", want, "MATCH" if got == want else "MISMATCH")
    if got != want:
        # The && chain means a failed decode left the tmp file behind (the rm never ran); clean
        # it up here, or a file as large as the original stays on the TV for nothing.
        dev.shell(f"rm -f {t}")
    dev.close()
    sys.exit(0 if got == want else 1)


if __name__ == "__main__":
    main()
