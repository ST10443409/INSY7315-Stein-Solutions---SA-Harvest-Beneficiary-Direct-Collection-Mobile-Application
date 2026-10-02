#!/bin/bash
# Manual verification for the role audit (#52): calls every endpoint as every role and compares the answer with the
# intended-access table in docs/role-audit-checklist.md (the same table api.Tests/RoleAuthorizationMatrixTests.cs enforces).
#
#   export CBO_TOKEN=... VETTING_TOKEN=... ADMIN_TOKEN=...      # from POST /api/auth/login
#   API=http://localhost:5000 bash docs/verify-roles.sh           # default: the Docker stack
#   API=https://<host> bash docs/verify-roles.sh                  # a deployed environment
#
# Exit status is 0 only when every answer matches. "allowed" means "not 401 and not 403": the calls send an empty body and an id
# that matches nothing, so an allowed caller gets a 400 or 404 and nothing is changed on the server.

API="${API:-http://localhost:5000}"

for v in CBO_TOKEN VETTING_TOKEN ADMIN_TOKEN; do
  if [ -z "${!v}" ]; then echo "Set $v first (see the comment at the top of this script)." >&2; exit 2; fi
done

# method route allowed-roles   (CBO, VETTING, ADMIN, or "ANY" = any valid token, or "OPEN" = no token needed)
TABLE="
POST api/cbo-collection/sync CBO,ADMIN
POST api/vetting/sync VETTING,ADMIN
GET api/vetting/records VETTING,ADMIN
GET api/admin/user-activity ADMIN
GET api/admin/sync-status ADMIN
GET api/admin/sync-status/attention ADMIN
GET api/admin/sync-status/no-such-record ADMIN
POST api/admin/sync-status/no-such-record/retry ADMIN
POST api/admin/sync-status/no-such-record/dismiss ADMIN
POST api/sync CBO,VETTING,ADMIN
GET api/health OPEN
GET api/auth/me ANY
"

fail=0
printf '%-6s %-48s %-10s %-9s %-9s %s\n' METHOD ENDPOINT CALLER EXPECTED ACTUAL RESULT
while read -r method route allowed; do
  [ -z "$method" ] && continue
  for caller in NONE CBO VETTING ADMIN; do
    header=()
    if [ "$caller" != NONE ]; then var="${caller}_TOKEN"; header=(-H "Authorization: Bearer ${!var}"); fi

    if [ "$allowed" = OPEN ]; then expect="not-403"
    elif [ "$caller" = NONE ]; then expect=401
    elif [ "$allowed" = ANY ] || [[ ",$allowed," == *",$caller,"* ]]; then expect="not-401/403"
    else expect=403; fi

    code=$(curl -s -o /dev/null -w "%{http_code}" -X "$method" "$API/$route" "${header[@]}" \
      -H "Content-Type: application/json" $([ "$method" = POST ] && echo "-d {}"))

    case "$expect" in
      401|403) [ "$code" = "$expect" ] && ok=1 || ok=0 ;;
      not-403) [ "$code" != 403 ] && ok=1 || ok=0 ;;
      *)       { [ "$code" != 401 ] && [ "$code" != 403 ]; } && ok=1 || ok=0 ;;
    esac
    [ $ok = 1 ] && result=ok || { result=MISMATCH; fail=1; }
    printf '%-6s %-48s %-10s %-9s %-9s %s\n' "$method" "$route" "$caller" "$expect" "$code" "$result"
  done
done <<< "$TABLE"

if [ $fail = 0 ]; then echo "All answers match the audit table."; else echo "MISMATCH: fix the endpoint (or the table) before release." >&2; fi
exit $fail
