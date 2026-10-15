#!/usr/bin/env bash
# Runs what .github/workflows/ci.yml runs on a release tag, on the dev PC, one step after another.
#
#   scripts/local-ci.sh [--out DIR] [STEP ...]
#
# Without steps it runs all of them. Steps: gates web server-1 .. server-5 server-openapi image deploy.
# One more step runs only when named: deps (osv-scanner on the lockfiles; needs the network, and its
# findings are written down when a slice closes, not judged here: ADR D-58).
# Every step writes DIR/<step>.log; DIR/summary.txt gets one line for each step (result, seconds).
# The exit code is 0 only when every step that ran passed, so a failed step can be run again alone.
#
# What differs from CI:
# - The server tests run in five parts, each in its own JVM, because the dev PC cannot hold every class
#   in one JVM next to Docker. A fault that needs all classes in one JVM does not show here.
# - gitleaks scans origin/main..HEAD, and the locked-path check takes origin/main as its base.
# - Maven runs offline (-o).
#
# POPDESK_HEAVY, when set, is put in front of the heavy commands with the step's name
# (for example a wrapper that waits for free memory): POPDESK_HEAVY="bash /path/heavy.sh".

set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT=""
STEPS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT="$2"; shift 2 ;;
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
    *) STEPS+=("$1"); shift ;;
  esac
done
ALL=(gates web server-1 server-2 server-3 server-4 server-5 server-openapi image deploy)
[ ${#STEPS[@]} -eq 0 ] && STEPS=("${ALL[@]}")
[ -z "$OUT" ] && OUT="$ROOT/server/target/local-ci"
mkdir -p "$OUT"
SUMMARY="$OUT/summary.txt"

# The server test packages of each part, about the same time each (seconds of the v0.4.0 tag run).
# deploy and e2e are the deploy step. `root` is the classes right under io.popdesk.
PART_1="notify root"
PART_2="ops audit due task link"
PART_3="auth start photo"
PART_4="issue inbox boundary manager label"
PART_5="attachment project helpdesk notification support"

TESTS="$ROOT/server/src/test/java/io/popdesk"

heavy() {  # heavy <step> <command...>
  local step="$1"; shift
  if [ -n "${POPDESK_HEAVY:-}" ]; then
    $POPDESK_HEAVY "$step" "$@"
  else
    "$@"
  fi
}

# Fails when a test package is in no part, so a new package is not left out without notice.
check_parts() {
  local known=" $PART_1 $PART_2 $PART_3 $PART_4 $PART_5 deploy e2e "
  local missing=""
  for dir in "$TESTS"/*/; do
    local name; name="$(basename "$dir")"
    case "$known" in *" $name "*) ;; *) missing="$missing $name" ;; esac
  done
  if [ -n "$missing" ]; then
    echo "local-ci: test packages in no part:$missing (add them to a PART_ line)" >&2
    return 1
  fi
}

# The -Dtest value of a part. Tests still waiting for implementation (pending.txt) are taken out here,
# because Surefire drops the excludes file when -Dtest is given.
test_pattern() {
  local pattern=""
  for name in $1; do
    if [ "$name" = root ]; then pattern="$pattern,io/popdesk/*"; else pattern="$pattern,io/popdesk/$name/**"; fi
  done
  local pending
  pending="$(cd "$ROOT" && python - <<'EOF'
from pathlib import Path
out = []
for f in sorted(Path('design/slices').glob('*/pending.txt')):
    for line in f.read_text(encoding='utf-8').splitlines():
        side, _, pattern = line.strip().partition(' ')
        if side == 'server' and pattern.strip():
            out.append('!' + pattern.strip())
print(','.join(out))
EOF
)"
  [ -n "$pending" ] && pattern="$pattern,$pending"
  echo "${pattern#,}"
}

run_step() {
  case "$1" in
    gates)
      cd "$ROOT" &&
      python scripts/ci/tests-lock.py check &&
      python scripts/ci/check-test-style.py &&
      python scripts/ci/check-tenant-id.py &&
      python scripts/ci/check-locked-paths.py --base origin/main --role any &&
      MSYS_NO_PATHCONV=1 docker run --rm -v "$(cygpath -m "$ROOT" 2>/dev/null || echo "$ROOT"):/repo" \
        ghcr.io/gitleaks/gitleaks:latest git /repo --config /repo/.gitleaks.toml \
        --log-opts="origin/main..HEAD" --no-banner --redact &&
      # The structure checks that start no app (ADR D-57, D-58). The server parts run them again.
      cd "$ROOT/server" &&
      heavy gates ./mvnw -o -B test         -Dtest=PackageCyclesTest,ApiPathPrefixTest,ProductionCodeRulesTest,SwallowedExceptionsTest,TransactionalPlacementTest
      ;;
    deps)
      cd "$ROOT" &&
      MSYS_NO_PATHCONV=1 docker run --rm -v "$(cygpath -m "$ROOT" 2>/dev/null || echo "$ROOT"):/src:ro"         ghcr.io/google/osv-scanner:latest scan source         --lockfile /src/server/pom.xml --lockfile /src/web/pnpm-lock.yaml
      ;;
    web)
      cd "$ROOT/web" &&
      pnpm install --frozen-lockfile &&
      pnpm gen:api && git diff --exit-code -- packages/api/src/generated &&
      pnpm -r run typecheck &&
      pnpm -r run lint &&
      heavy web sh -c 'pnpm -r --workspace-concurrency=1 run test $(python ../scripts/ci/pending.py web)' &&
      heavy web sh -c 'pnpm --filter @popdesk/ui run test-storybook $(python ../scripts/ci/pending.py web)' &&
      pnpm -r run build &&
      pnpm dup &&
      python ../scripts/ci/check-portal-bundle.py portal/dist
      ;;
    server-[1-5])
      local n="${1#server-}" part
      eval "part=\$PART_$n"
      check_parts && cd "$ROOT/server" &&
      heavy "$1" ./mvnw -o -B test "-Dtest=$(test_pattern "$part")" -Dsurefire.failIfNoSpecifiedTests=false
      ;;
    server-openapi)
      cd "$ROOT/server" &&
      heavy "$1" ./mvnw -o -B -Popenapi test &&
      git diff --exit-code -- ../docs/integrate/openapi-portal.json ../docs/integrate/openapi-console.json
      ;;
    image)
      cd "$ROOT" && heavy image docker build -t popdesk:test .
      ;;
    deploy)
      cd "$ROOT/server" &&
      heavy deploy sh -c './mvnw -o -B test -Dtest.groups=deploy -Dtest.excludedGroups=openapi $(python ../scripts/ci/pending.py server)'
      ;;
    *)
      echo "local-ci: no step $1 (steps: ${ALL[*]} deps)" >&2
      return 2
      ;;
  esac
}

failed=0
for step in "${STEPS[@]}"; do
  start=$(date +%s)
  echo "local-ci: $step ..."
  ( run_step "$step" ) > "$OUT/$step.log" 2>&1
  code=$?
  seconds=$(( $(date +%s) - start ))
  tests="$(tr -d '\r' < "$OUT/$step.log" | grep -aE '^\[(INFO|ERROR|WARNING)\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' | tail -1)"
  if [ $code -eq 0 ]; then result=ok; else result="FAILED($code)"; failed=1; fi
  line="$(date +%F' '%T) $step $result ${seconds}s $(git -C "$ROOT" rev-parse --short HEAD) ${tests#*] }"
  echo "$line" | tee -a "$SUMMARY"
done
exit $failed
