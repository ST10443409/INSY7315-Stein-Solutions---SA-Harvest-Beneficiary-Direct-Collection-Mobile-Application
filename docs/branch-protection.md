# Branch protection (`development` and `main`)

Design document B10.1: three kinds of branch (`feature/*`, `development`, `main`); direct pushes to `development` and `main`
are off; every merge is a pull request (the team waived the approval, see below); a failing build or test blocks the merge.

This is a GitHub **setting**, not something a commit can carry, so it is applied once with the commands below. It cannot be
applied until `development` exists on GitHub.

## Rules (identical for both branches)

| Setting | Value | Why |
|---|---|---|
| Pull request required | yes, **0 approvals** (decided: team of two) | B10.1 asks for 1 approval; waived by the team. The PR and the checks are still required. |
| Dismiss stale approvals on new commits | yes | An approval should cover what was merged. |
| Required checks | `build-test-docker`, `build`, `gitleaks` | The three job names. `build-test-docker` and `build` are *skipped* (which counts as passed) when their half of the repo did not change; `gitleaks` always runs. |
| Branch must be up to date before merge | no | With two or three people it mostly causes re-runs. Turn on later if `development` starts to break. |
| Resolve conversations before merge | yes | |
| Force pushes / deleting the branch | blocked | |
| Apply to administrators | **no** (decided: the owner, ST10443409, may bypass) | With yes, even the owner must use a PR with an approval. With no, the owner can push straight to the branch in an emergency; the audit log still shows it. |

`development` -> `main` is the release path. GitHub cannot enforce "only `development` may be merged into `main`" with
branch protection alone; it is a team rule (and the deploy workflow will later only build what reached `main`).

## Does the student plan allow it?

Branch protection on a **private** repository needs GitHub Pro (or Team). The GitHub Student Developer Pack includes Pro for
the account that applies for it, and this repository belongs to a personal account (`ST10443409`), so that account must have
Pro active. The API reports `"plan": null` for this token, so check by running the commands: on a plan without it the
second call returns `403 ... Upgrade to GitHub Pro or make this repository public`. Nothing is changed in that case.

## Order of operations

1. Create `development` from `main` and push it (`git switch -c development && git push -u origin development`). The
   CI-trigger change is already on `main` (`c22cf57`), so the branch inherits it. That first push runs all three workflows
   once (a new branch has no "before" commit, so nothing is skipped; the emulator tests do not run on `development`).
2. Open one small pull request into `development` (a docs change is enough) so the checks have also reported on a pull
   request; GitHub's UI only offers checks that have reported recently. (The API call below accepts the names without this.)
3. Apply the rules:

```bash
REPO=ST10443409/INSY7315-Stein-Solutions---SA-Harvest-Beneficiary-Direct-Collection-Mobile-Application

for BRANCH in development main; do
gh api -X PUT "repos/$REPO/branches/$BRANCH/protection" --input - <<'JSON'
{
  "required_status_checks": { "strict": false, "contexts": ["build-test-docker", "build", "gitleaks"] },
  "enforce_admins": false,
  "required_pull_request_reviews": { "required_approving_review_count": 0, "dismiss_stale_reviews": true },
  "restrictions": null,
  "required_conversation_resolution": true,
  "allow_force_pushes": false,
  "allow_deletions": false
}
JSON
done
```

4. Check it took effect:

```bash
gh api "repos/$REPO/branches/development/protection" --jq '{checks: .required_status_checks.contexts, approvals: .required_pull_request_reviews.required_approving_review_count, admins: .enforce_admins.enabled}'
```

5. Make `development` the branch pull requests default to (optional; the default is still `main`):
   `gh repo edit "$REPO" --default-branch development`. Do this only if the team wants new PRs to target `development` by
   default; the deploy-on-push-to-`main` rule is unaffected by the default branch.

## When a check name changes

The required names are the **job** names in the workflows (`build-test-docker`, `build`, `gitleaks`). Renaming a job
without updating the rule makes every pull request wait for a check that will never report. Change both together.
