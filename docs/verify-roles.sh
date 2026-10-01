#!/bin/bash
# Manual verification script for Issue #52
# Requires CBO_TOKEN, VETTING_TOKEN, and ADMIN_TOKEN to be set in the environment.

echo "Endpoint | Token | HTTP Code"
echo "----------------------------------------"

for endpoint in cbo-collection/sync vetting/sync vetting/records admin/sync-status admin/user-activity sync; do
  for token_var in CBO_TOKEN VETTING_TOKEN ADMIN_TOKEN; do
    code=$(curl -s -o /dev/null -w "%{http_code}" https://localhost:5000/api/$endpoint \
      -H "Authorization: Bearer ${!token_var}")
    echo "$endpoint | $token_var -> $code"
  done
done
