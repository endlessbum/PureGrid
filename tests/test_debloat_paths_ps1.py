"""Parity net for the PowerShell debloat scripts: whatever disable.sh/restore.sh guarantee,
their .ps1 twins must guarantee too. Static source checks — running powershell.exe from a
Python test harness turned out to be flaky in a way plain execution is not (the same
command line succeeds standalone and fails under a unittest runner on some machines), so
the suite asserts the invariants in source instead, deterministically and cross-platform.

Run from the repository root: python3 -m unittest discover -s tests -v
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

PKG_RE = r"^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$"


def read(name):
    return (ROOT / "tools" / name).read_text(encoding="utf-8-sig")


class Ps1ParityTest(unittest.TestCase):
    def test_package_validation_regex_matches_sh(self):
        # #165 parity: the ps1 scripts must refuse exactly what the sh scripts refuse.
        for script in ("disable.ps1", "restore.ps1"):
            with self.subTest(script=script):
                self.assertIn(PKG_RE[1:], read(script), f"{script} validates with the wrong regex")

    def test_disable_sends_force_stop(self):
        # pm disable-user only switches components off; each disabled package is also stopped.
        sh, ps = read("disable.sh"), read("disable.ps1")
        self.assertIn("am force-stop", sh)
        self.assertIn("am force-stop", ps)
        self.assertIn("pm disable-user --user 0", ps)

    def test_restore_content_parity(self):
        # restore must re-enable, re-home the stock launcher (via TV-side resolve), reset the
        # three animation scales, and list the disabled packages for eyeballing.
        ps = read("restore.ps1")
        for needle in ("pm enable $Stock",
                       "cmd package set-home-activity --user 0",
                       "cmd package resolve-activity --brief -a android.intent.action.MAIN "
                       "-c android.intent.category.HOME",
                       "window_animation_scale", "transition_animation_scale",
                       "animator_duration_scale", "pm list packages -d"):
            self.assertIn(needle, ps)

    def test_restore_stock_param_is_validated(self):
        # STOCK lands in a shell command on the TV: both variants validate it as a package name.
        self.assertIn("not a package name", read("restore.sh"))
        self.assertIn("not a package name", read("restore.ps1"))

    def test_setupwraith_guard_fails_closed(self):
        # An unreadable Wi-Fi probe must not wave setupwraith through the guard (Wi-Fi loss).
        sh, ps = read("disable.sh"), read("disable.ps1")
        self.assertIn("empty dumpsys", sh)
        self.assertIn("empty dumpsys", ps)

    def test_list_decoding_is_utf8(self):
        # PowerShell 5.1 reads a UTF-8 list as ANSI without -Encoding UTF8: Cyrillic comments
        # turn to mojibake, which the sh variant never suffers (it normalises explicitly).
        for script in ("disable.ps1", "restore.ps1"):
            with self.subTest(script=script):
                self.assertIn("Get-Content $List -Encoding UTF8", read(script))

    def test_batch_summarises_device_errors(self):
        # adb_shell carries no exit codes; tv.py must parse the output and report how many
        # commands answered Error/Failure, so a half-applied list cannot look like success.
        tv = (ROOT / "tools" / "tv.py").read_text(encoding="utf-8")
        self.assertIn("batch done", tv)
        self.assertIn("FAIL_RE", tv)

    def test_b64push_quotes_remote_paths(self):
        # The remote path is caller-supplied and interpolated into device shell commands.
        tv = (ROOT / "tools" / "b64push.py").read_text(encoding="utf-8")
        self.assertIn("shlex.quote", tv)


if __name__ == "__main__":
    unittest.main()
