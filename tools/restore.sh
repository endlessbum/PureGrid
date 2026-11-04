#!/usr/bin/env bash
# Re-enable every package listed in a debloat file, restore the stock launcher as home and reset
# animation scales. Pass the same list you used with tools/disable.sh.
#
# Usage: TV_HOST=192.168.1.50 tools/restore.sh examples/xiaomi-mi-tv-4a/debloat.txt [stock.launcher.package]
set -euo pipefail
LIST="${1:?usage: restore.sh <debloat list> [stock launcher package]}"
# Resolve the list against the caller's directory before moving into the repo.
LIST_DIR="$(cd "$(dirname "$LIST")" && pwd)"
LIST="$LIST_DIR/$(basename "$LIST")"
cd "$(dirname "$0")/.."
STOCK="${2:-com.google.android.tvlauncher}"
# STOCK is pasted into a shell command on the TV just like the list entries: accept only a
# package name (no "none" here — pass the real stock launcher, this script re-enables it).
if ! printf '%s' "$STOCK" | grep -qE '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$'; then
  echo "refusing STOCK='$STOCK': not a package name" >&2
  exit 1
fi
PY=tools/venv/bin/python
[ -x "$PY" ] || PY=tools/venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1
# Same check as disable.sh: the list is contributor-supplied and each entry becomes a shell
# command on the TV. A BOM and CRLF from a Windows editor are dropped first (#165, #208).
# [[:space:]], not \s: BSD grep/sed on macOS do not honour \s in ERE, and a missed indented
# comment would arrive here as a "package name" and refuse the whole list.
PKGS=$(LC_ALL=C awk 'NR==1{sub(/^\357\273\277/,"")} {sub(/\r$/,""); print}' "$LIST" \
  | grep -vE '^[[:space:]]*(#|$)' | sed -E 's/[[:space:]]+#.*$//' | awk '{print $1}')
BAD=$(printf '%s\n' "$PKGS" | grep -vE '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$' || true)
if [ -n "$BAD" ]; then
  { echo "refusing $LIST: not a package name:"; printf '%s\n' "$BAD" | sed 's/^/  /'; } >&2
  exit 1
fi
BATCH_EXIT=0
{
  [ -n "$PKGS" ] && printf '%s\n' "$PKGS" | sed 's/^/pm enable /'
  echo "pm enable $STOCK"
  # resolve the stock HOME component on the TV (Android 9 needs pkg/activity, not just pkg)
  echo "cmd package set-home-activity --user 0 \$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $STOCK | tail -n 1)"
  echo "settings put global window_animation_scale 1.0"
  echo "settings put global transition_animation_scale 1.0"
  echo "settings put global animator_duration_scale 1.0"
  echo "pm list packages -d"
} | "$PY" -I tools/tv.py --batch || BATCH_EXIT=$?
if [ "$BATCH_EXIT" -ne 0 ]; then
  echo "WARNING: some commands answered Error/Failure (see the batch summary above). Check where Home" >&2
  echo "resolves to: TV_HOST=<tv> tools/tv.py 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'" >&2
  echo "(see docs/TROUBLESHOOTING.md for the 'choose a Home app' screen after a restore)." >&2
fi
# Where Home actually points after the restore: set-home-activity can silently fail to stick
# (e.g. the stock launcher was reinstalled and its component does not resolve). A NOTE, not an
# error — the restore itself worked; this just says the last step out loud. The probe is best
# effort: a dead connection here must not fail the whole restore.
HOME_NOW=$(("$PY" -I tools/tv.py 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' 2>/dev/null || true) | tail -n 1 | tr -d '\r' | cut -d/ -f1)
if [ "$HOME_NOW" != "$STOCK" ]; then
  echo "NOTE: Home now resolves to '${HOME_NOW:-nothing}', not $STOCK. Run the set-home-activity line by hand, or see docs/TROUBLESHOOTING.md." >&2
fi
exit "$BATCH_EXIT"
