#!/usr/bin/env python3
"""Minimal ADB-over-TCP client for an Android TV using adb_shell (no adb binary needed).

Set TV_HOST to the TV's IP (TV_PORT overrides the default 5555). Usage:
    tv.py --probe                      connect and print model / Android version
    tv.py <shell command...>           run one shell command
    tv.py --batch < commands.txt       one command per line, same connection
    tv.py --push <local> <remote>      small files only (see b64push.py for larger ones)
    tv.py --pull <remote> <local>
"""
import hashlib
import os
import re
import shlex
import sys

from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.keygen import keygen
from adb_shell.auth.sign_pythonrsa import PythonRSASigner

HOST = os.environ.get("TV_HOST")
if not HOST:
    sys.exit("Set TV_HOST to your TV's IP address, e.g.  TV_HOST=192.168.1.50 tools/tv.py --probe\n"
             "(find it under Settings → Network, or look for the TV in `arp -a`)")
try:
    PORT = int(os.environ.get("TV_PORT", "5555"))
except ValueError:
    sys.exit(f"TV_PORT='{os.environ.get('TV_PORT')}' is not a port number")
KEYDIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "adbkey")
KEY = os.path.join(KEYDIR, "adbkey")
# Reuse the stock adb key when the TV already trusts it (platform-tools / Android Studio users), so the
# "Allow USB debugging?" prompt doesn't come back a second time for this client. From #105.
ADB_KEY = os.path.join(os.path.expanduser("~"), ".android", "adbkey")
if not os.path.exists(KEY) and os.path.exists(ADB_KEY) and os.path.exists(ADB_KEY + ".pub"):
    KEY = ADB_KEY


def get_signer():
    os.makedirs(KEYDIR, exist_ok=True)
    if not os.path.exists(KEY):
        keygen(KEY)
    with open(KEY) as f:
        priv = f.read()
    with open(KEY + ".pub") as f:
        pub = f.read()
    return PythonRSASigner(pub, priv)


def connect(timeout=120):
    dev = AdbDeviceTcp(HOST, PORT, default_transport_timeout_s=float(timeout))
    try:
        dev.connect(rsa_keys=[get_signer()], transport_timeout_s=float(timeout), auth_timeout_s=timeout)
    except Exception as e:
        sys.exit(f"Cannot reach {HOST}:{PORT}: {e}\n"
                 "Check that TV_HOST is right, the TV is on, and wireless debugging is on.\n"
                 "If the TV shows an 'Allow USB debugging?' prompt, accept it. See docs/REMOTE.md.")
    if not getattr(dev, "available", True):
        sys.exit(f"{HOST}:{PORT} did not accept the connection (adb_shell reports it unavailable).\n"
                 "If the TV shows an 'Allow USB debugging?' prompt, accept it. See docs/REMOTE.md.")
    return dev


# A shell command that the device answered with an error line: `pm` prints "Error: ..." or
# "Failure ..." instead of exiting non-zero, and adb_shell does not carry exit codes, so the
# text is the only failure signal there is.
FAIL_RE = re.compile(r"^(Error:|Failure)", re.MULTILINE | re.IGNORECASE)


def run_batch(dev):
    """One shell command per stdin line, each run separately on the same connection.

    utf-8-sig drops the BOM Windows PowerShell may prepend to piped stdin. Device-side errors are
    counted: a half-applied list must not look like a success just because the transport worked.
    """
    sys.stdin.reconfigure(encoding="utf-8-sig")
    total = failed = 0
    first_failure = None
    for line in sys.stdin:
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        total += 1
        try:
            out = dev.shell(line, read_timeout_s=120.0)
        except Exception as e:
            # A lost connection would otherwise surface as a raw traceback with the same exit
            # code as "some commands failed", hiding which of the two happened. Exit 2: the
            # callers treat any non-zero as "may be half-applied", the message says more.
            print(f"\nconnection lost after {total - failed}/{total} commands: {e}\n"
                  "The list may be half-applied — re-run the script, or undo with tools/restore.sh.")
            return 2
        sys.stdout.write(f"$ {line}\n{out}")
        if out and not out.endswith("\n"):
            sys.stdout.write("\n")
        sys.stdout.flush()
        if FAIL_RE.search(out or ""):
            failed += 1
            if first_failure is None:
                first_failure = line
    print(f"\nbatch done: {total - failed}/{total} commands clean"
          + (f"; {failed} answered Error/Failure, first: {first_failure}" if failed else ""))
    return 1 if failed else 0


def push_verified(dev, local, remote):
    """Push and prove the bytes landed intact: the SYNC transfer is silent about corruption, and
    b64push.py verifies md5 for its own stream, so a plain push must not be the unguarded path."""
    with open(local, "rb") as f:
        want = hashlib.md5(f.read()).hexdigest()
    dev.push(local, remote, transport_timeout_s=300.0, read_timeout_s=300.0)
    out = dev.shell(f"md5sum {shlex.quote(remote)}", read_timeout_s=120.0)
    got = out.split()[0] if out and out.split() else ""
    if got.lower() != want.lower():
        sys.exit(f"pushed {local} -> {remote}, but md5 differs (local {want}, remote '{got or 'no answer'}');\n"
                 "the transfer was corrupted — retry, or push via tools/b64push.py which verifies per chunk")
    print(f"pushed {local} -> {remote} (md5 ok)")


def main():
    args = sys.argv[1:]
    # Answer usage mistakes before any network: a wrong argument count must not first spend a TCP
    # connect — and possibly an "Allow USB debugging?" prompt on the TV — on a doomed call.
    if args and args[0] in ("--push", "--pull") and len(args) != 3:
        sys.exit(f"usage: tv.py {args[0]} <local> <remote>" if args[0] == "--push"
                 else "usage: tv.py --pull <remote> <local>")
    if args and args[0] == "--batch" and len(args) != 1:
        sys.exit("usage: tv.py --batch < commands.txt")
    # Device output carries arbitrary text (network names, dumpsys dumps): on Windows the console
    # code page would raise UnicodeEncodeError in the middle of a batch. Replace, don't crash.
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    dev = connect()
    code = 0
    if not args or args == ["--probe"]:
        for p in ("ro.product.model", "ro.product.device", "ro.build.version.release",
                  "ro.build.version.sdk", "ro.build.display.id", "ro.product.manufacturer"):
            print(f"{p} = {dev.shell(f'getprop {p}').strip()}")
    elif args[0] == "--push":
        push_verified(dev, args[1], args[2])
    elif args[0] == "--pull":
        dev.pull(args[1], args[2], transport_timeout_s=300.0, read_timeout_s=300.0)
        print(f"pulled {args[1]} -> {args[2]}")
    elif args[0] == "--batch":
        code = run_batch(dev)
    else:
        out = dev.shell(" ".join(args), read_timeout_s=120.0)
        sys.stdout.write(out)
    dev.close()
    sys.exit(code)


if __name__ == "__main__":
    main()
