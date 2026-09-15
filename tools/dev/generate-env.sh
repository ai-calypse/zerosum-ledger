#!/usr/bin/env bash
# decision: D00-8 — docs/step_00_foundations.md#decisions-and-outputs
# Creates .env from .env.example, replacing every __GENERATE__ placeholder with `openssl rand -hex 32`
# (hex output, so no `$` or quotes can break Compose interpolation). Used locally and by the CI e2e job.
#
#   tools/dev/generate-env.sh            refuse to overwrite an existing .env
#   tools/dev/generate-env.sh --force    regenerate every value
set -euo pipefail

cd "$(dirname "$0")/../.."

if [[ -e .env && "${1:-}" != "--force" ]]; then
  echo "generate-env: .env already exists; rerun with --force to regenerate every value" >&2
  exit 1
fi

tmp=$(mktemp .env.XXXXXX)
trap 'rm -f "$tmp"' EXIT

while IFS= read -r line || [[ -n "$line" ]]; do
  line=${line%$'\r'}
  while [[ "$line" == *__GENERATE__* ]]; do
    line=${line/__GENERATE__/$(openssl rand -hex 32)}
  done
  printf '%s\n' "$line"
done < .env.example > "$tmp"

if grep -q '__[A-Z_]*__' "$tmp"; then
  echo "generate-env: placeholder markers remain in the generated file" >&2
  exit 1
fi

chmod 600 "$tmp"
mv "$tmp" .env
trap - EXIT
echo "generate-env: wrote .env"
