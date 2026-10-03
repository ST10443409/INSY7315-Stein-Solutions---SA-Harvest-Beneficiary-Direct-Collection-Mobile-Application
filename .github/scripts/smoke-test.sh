#!/usr/bin/env bash
# Smoke test of a deployed API (design document B10.3: health check, then a round trip). Exits non-zero, with the reason, on the
# first thing that is wrong; the deploy workflow rolls back when it does.
#
#   smoke-test.sh https://<host>
#
# Optional environment:
#   SMOKE_USERNAME / SMOKE_PASSWORD  an account that exists in that environment. When both are set the test also signs in and
#                                    asks who it is; when not, that step is skipped (a fresh environment has no accounts: there
#                                    is no way to create one outside Development yet, see docs/azure-deployment-readiness.md).
#   SMOKE_WAIT_SECONDS               how long to wait for a freshly started container to answer (default 180).
#   EXPECTED_REVISION                the build revision (first 12 characters of the commit) the new image reports in /api/health.
#                                    When set, the test keeps waiting while the PREVIOUS image is still the one answering, and fails
#                                    if the new one never takes over. Without it a deployment that silently failed to switch, so the
#                                    old container kept serving, would pass.
#
# What each check proves, so a failure points at the cause:
#   health 200   the container started, pulled its secrets from Key Vault, reached the database and applied its migrations
#   /me -> 401   the route and JWT validation are live, and the HTTPS/proxy settings (Security__KnownNetworks, AllowedHosts) are
#                right: wrong ones answer 403 HTTPS_REQUIRED or 400 here instead
#   login -> 401 the login endpoint works end to end against the database (a wrong password is refused, not an error)
#   sign-in      (optional) a real account can sign in and its role comes back
# Never prints a token or a password.
set -uo pipefail

base="${1:?usage: smoke-test.sh https://<host>}"
base="${base%/}"
wait="${SMOKE_WAIT_SECONDS:-180}"
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

fail() { echo "SMOKE TEST FAILED: $*" >&2; exit 1; }
ok()   { echo "ok: $*"; }

# curl prints 000 itself when it cannot connect (and exits non-zero), so the exit code is deliberately ignored.
status() { curl -s -o "$tmp" -w '%{http_code}' --max-time 20 "$@" || true; }

# 1. Health, retried while a new container starts and its image is pulled. With EXPECTED_REVISION it must also be the NEW image
#    answering: until then the previous container may still be serving, which is not a pass.
deadline=$((SECONDS + wait))
seen="nothing"
while true; do
  code="$(status "$base/api/health")"
  if [ "$code" = "200" ] && grep -q '"status":"ok"' "$tmp"; then
    seen="$(sed -nE 's/.*"revision":"([^"]*)".*/\1/p' "$tmp")"
    if [ -z "${EXPECTED_REVISION:-}" ] || [ "$seen" = "$EXPECTED_REVISION" ]; then break; fi
  fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    if [ -n "${EXPECTED_REVISION:-}" ] && [ "$code" = "200" ]; then
      fail "the API is healthy but still reports revision '${seen:-none}', not the expected '$EXPECTED_REVISION', after ${wait}s: the new image never took over"
    fi
    fail "GET /api/health did not answer 200 with status ok within ${wait}s (last status $code)"
  fi
  sleep 5
done
ok "GET /api/health is 200 and healthy${EXPECTED_REVISION:+, running revision $seen}"

# 2. A protected route without a token is refused as unauthenticated, not as a proxy or host problem.
code="$(status "$base/api/auth/me")"
[ "$code" = "401" ] || fail "GET /api/auth/me without a token answered $code, expected 401 ($(head -c 200 "$tmp"))"
ok "GET /api/auth/me without a token is 401"

# 3. A wrong password is refused with 401 (and not rate-limited, not an error).
code="$(status -X POST -H 'Content-Type: application/json' -d '{"username":"smoke-test-no-such-user","password":"wrong"}' "$base/api/auth/login")"
[ "$code" = "401" ] || fail "POST /api/auth/login with a wrong password answered $code, expected 401 ($(head -c 200 "$tmp"))"
ok "POST /api/auth/login with a wrong password is 401"

# 4. Optional: a real account signs in and its role comes back.
if [ -n "${SMOKE_USERNAME:-}" ] && [ -n "${SMOKE_PASSWORD:-}" ]; then
  body="$(printf '{"username":"%s","password":"%s"}' "$SMOKE_USERNAME" "$SMOKE_PASSWORD")"
  code="$(status -X POST -H 'Content-Type: application/json' -d "$body" "$base/api/auth/login")"
  [ "$code" = "200" ] || fail "sign-in as the smoke-test account answered $code, expected 200"
  token="$(sed -nE 's/.*"token":"([^"]+)".*/\1/p' "$tmp")"
  [ -n "$token" ] || fail "sign-in answered 200 but returned no token"
  code="$(status -H "Authorization: Bearer $token" "$base/api/auth/me")"
  [ "$code" = "200" ] || fail "GET /api/auth/me with the smoke-test account's token answered $code, expected 200"
  grep -q '"role"' "$tmp" || fail "GET /api/auth/me answered 200 but named no role"
  ok "the smoke-test account can sign in and is recognised"
else
  echo "skipped: sign-in (SMOKE_USERNAME / SMOKE_PASSWORD not set)"
fi

echo "SMOKE TEST PASSED: $base"
