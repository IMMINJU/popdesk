#!/usr/bin/env bash
# Backs up popdesk's database on a schedule (docs/operate/backup.md "Back up on a schedule").
#
#   deploy/backup.sh [-f COMPOSE_FILE]... [-p PROJECT] [-e ENV_FILE] [-d DIR] [-k KEEP]
#
# Writes a dump with `popdesk backup` inside the app container, copies it out with `docker compose cp`,
# deletes it in the container, and keeps it as DIR/popdesk-YYYYMMDD-HHMMSS.dump. Then deletes the oldest
# dumps in DIR so that KEEP are left. On a failure the exit code is not 0 and no partial file is left
# under the final name.
#
# Every option can also come from the environment:
#   POPDESK_BACKUP_COMPOSE_FILES  Compose files, separated by ':' (default: compose.yml in this folder)
#   POPDESK_BACKUP_PROJECT        Compose project name (default: what Compose picks)
#   POPDESK_BACKUP_ENV_FILE       .env file (default: the one next to the first Compose file)
#   POPDESK_BACKUP_DIR            where the dumps go (default: ./backups)
#   POPDESK_BACKUP_KEEP           how many dumps to keep (default: 24)
#
# The dump holds no attachment files when POPDESK_STORAGE is disk or s3: back those up after this.
set -euo pipefail
# Dumps hold personal data: only the owner reads them.
umask 077
# Git Bash on Windows would turn the container paths below into Windows paths; the host path is
# turned into one by hand instead (cygpath).
export MSYS_NO_PATHCONV=1

here="$(cd "$(dirname "$0")" && pwd)"
IFS=':' read -r -a files <<< "${POPDESK_BACKUP_COMPOSE_FILES:-$here/compose.yml}"
project="${POPDESK_BACKUP_PROJECT:-}"
env_file="${POPDESK_BACKUP_ENV_FILE:-}"
dir="${POPDESK_BACKUP_DIR:-./backups}"
keep="${POPDESK_BACKUP_KEEP:-24}"
given_files=()
while getopts "f:p:e:d:k:h" option; do
  case "$option" in
    f) given_files+=("$OPTARG") ;;
    p) project="$OPTARG" ;;
    e) env_file="$OPTARG" ;;
    d) dir="$OPTARG" ;;
    k) keep="$OPTARG" ;;
    h) sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) exit 2 ;;
  esac
done
[ "${#given_files[@]}" -gt 0 ] && files=("${given_files[@]}")
if ! [[ "$keep" =~ ^[1-9][0-9]*$ ]]; then
  echo "backup.sh: KEEP must be a whole number of 1 or more, not '$keep'" >&2
  exit 2
fi

compose=(docker compose)
[ -n "$project" ] && compose+=(-p "$project")
for f in "${files[@]}"; do compose+=(-f "$f"); done
[ -n "$env_file" ] && compose+=(--env-file "$env_file")

mkdir -p "$dir"
stamp="$(date -u +%Y%m%d-%H%M%S)"
name="popdesk-$stamp.dump"
inside="/tmp/popdesk-backup-$stamp.dump"
partial="$dir/.$name.partial"

cleanup() {
  rm -f "$partial"
  "${compose[@]}" exec -T app rm -f "$inside" >/dev/null 2>&1 || true
}
trap cleanup EXIT

"${compose[@]}" exec -T app popdesk backup "$inside"
host_partial="$partial"
command -v cygpath >/dev/null 2>&1 && host_partial="$(cygpath -m "$partial")"
"${compose[@]}" cp "app:$inside" "$host_partial"
if [ ! -s "$partial" ]; then
  echo "backup.sh: the copied dump is empty" >&2
  exit 1
fi
mv "$partial" "$dir/$name"
echo "backup.sh: wrote $dir/$name ($(wc -c < "$dir/$name") bytes)"

# Keep the newest KEEP dumps. The names sort by time, oldest first.
mapfile -t dumps < <(find "$dir" -maxdepth 1 -type f -name 'popdesk-*.dump' -printf '%f\n' | sort)
remove=$(( ${#dumps[@]} - keep ))
for (( i = 0; i < remove; i++ )); do
  rm -f "$dir/${dumps[$i]}"
  echo "backup.sh: deleted $dir/${dumps[$i]}"
done
