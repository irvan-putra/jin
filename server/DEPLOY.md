# Redeploying the hosted jin service on NEO App

Exact recipe to stand the hosted jin bot back up from zero, based on the
verified trial of 2026-11-23 (deployed, exercised end to end, then torn
down). Budget about 15 minutes. Everything below ran against
https://portal.app.neoapp.id; keep this file free of credentials, keys and
account-specific values - placeholders only, the repo is public.

## What a human must supply

- The NEO account with prepaid credits, and a fresh **API key**: portal ->
  Developer Tools -> API tokens (a `kap_...` key). Keys expire; the trial
  hit an expired key (HTTP 401 `invalid API token` from
  `GET /api/v1/auth/me`). Check auth first, mint a new key on 401.
- The **transfer code** (`JIN_TRANSFER_CODE`): reuse the saved one to
  restore the previous bot identity, or generate a fresh one for a new
  identity:
  `python3 -c "import secrets,base64;print(base64.urlsafe_b64encode(secrets.token_bytes(32)).decode())"`
  Without the old code, old state bundles are unreadable and the bot
  starts as a new account.
- Account sign-in / key handling stays with the owner; agents never
  register accounts or hold the key beyond a single run.

## Tooling

- Official CLI: `npx -y @biznetgio/neo-app@latest` (its MCP verbs below,
  `neo_*`). Official agent instructions: https://portal.app.neoapp.id/prompt.md
- Or direct REST: `https://portal.app.neoapp.id/api/v1/...` with
  `Authorization: Bearer <kap_ key>`.
- Pin the environment (`production`) once per session; the CLI errors on
  env-requiring calls until pinned. Keep jin in its OWN project, separate
  from other apps (the trial used a dedicated project so Naung stayed
  untouched).

## Cost picture (trial config, 2026-11-23, before tax)

Config: app 0.5 CPU / 512Mi RAM / 1 fixed always-on replica (no volumes,
no autoscaling) + managed PostgreSQL 16 (single, 10Gi, private) + private
bucket (10GB).

- **Configured rate: ~Rp252.50/hour** before tax (CPU ~Rp25, RAM up to
  Rp37.50, DB ~Rp40, bucket ~Rp150). That is ~Rp6,060/day, ~Rp182k per
  30 days, before tax.
- The trial's own bill was Rp176 before tax - but that covered only its
  ~45-minute life (RAM 19, CPU 14, DB 30, bucket 113), sampled from the
  project's `/usage` endpoint. Rp176 is NOT a daily figure.
- Estimate before creating anything: `POST /api/v1/orgs/{orgId}/estimate`
  (or `neo_estimate`). Billable creates pass a server-side confirmation
  gate: the first call previews cost and creates nothing; re-call with
  `confirm:true` (or approve via `neo_confirm_action`) to execute.
- Billing lesson carried from Naung and re-verified in the trial: deleting
  resources is not proof that charges stopped. Always confirm the
  project's `/usage` lines afterwards.

## Procedure (calls in order)

1. **Auth check** - `neo_whoami` (or `GET /api/v1/auth/me`). On 401, mint
   a fresh API key in the portal first.
2. **Project** - `neo_create_project {name, description}`. Note the
   project id.
3. **Database** - `neo_create_database {projectId, name:"jin-state",
   engine:"postgres", version:"16", environment:"production",
   volumeSize:"10Gi", deploymentMode:"single", replicas:1}` (preview),
   then the same call with `confirm:true`. Then **start it**:
   `neo_deploy_database` (creation leaves it `not_deployed`). Poll
   `neo_get_database` until `status: deployed` with host
   `jin-state-rw`, port 5432 (cnpg provisioner, private). The
   connection string is write-only by design: credentials only reach the
   app through a binding (step 6), never printed.
4. **Bucket** - `neo_create_bucket {projectId, name:"jin-blobs",
   acl:"private", quotaGB:10}` (preview), then `confirm:true`. Note the
   endpoint (`https://nos.wjv-1.neo.id` in the trial). The bucket
   resource's `region` field comes back EMPTY - see gotcha 1.
5. **App** - deploy from this repo with `server/` as the source folder
   (its Dockerfile makes buildType `dockerfile`):
   - CLI: the folder deploy verb (`neo_deploy_folder`-family) on `server/`.
   - REST equivalent, in order: `POST /api/v1/projects/{projectId}/apps`
     (buildType dockerfile) -> `POST /api/v1/apps/{appId}/source`
     (folder tarball) -> `POST /api/v1/apps/{appId}/deploy`.
   - Config: `cpuLimit "0.5"`, `memoryLimit "512Mi"`, replicas 1 fixed
     always-on, no volumes, no autoscaling.
6. **Bindings** - bind the database and the bucket to the app (CLI
   binding verbs). Bindings inject `JIN_DB_DSN` and the `JIN_S3_*` set
   into the service; binding-injected vars take precedence over
   per-service vars, so do not duplicate them by hand.
7. **Per-service env vars** - `neo_set_service_variable {appId, key,
   value, secret:true}`. `secret:true` stores the value as a cluster
   Secret and it is never echoed back (this answers the old "does NEO
   have secret env" question: yes). A redeploy is required for changes to
   take effect. Set:
   - `JIN_TRANSFER_CODE` (secret) - see "What a human must supply".
   - `JIN_S3_SIGNING_REGION=WJV-1` - gotcha 1, mandatory with the typed
     bucket binding.
   - Optional: `JIN_CHATMAIL_RELAY` (default `nine.testrun.org`),
     `JIN_CHECKPOINT_INTERVAL_S` (900), `JIN_BUCKET_POLL_S` (60),
     `PORT` (8080).
8. **Redeploy and verify** - see the checklist below. Do not trust a
   green `/status` alone (gotchas 2-4).

## Gotchas (every one hit during the 2026-11-23 trial)

1. **Empty binding signing region.** The typed bucket binding injects
   `JIN_S3_REGION` as an empty string, and binding vars override
   per-service vars. The service therefore reads
   `JIN_S3_SIGNING_REGION` first (commit 3620637); set it to the verified
   signing region (`WJV-1` for the `nos.wjv-1.neo.id` endpoint, confirmed
   via a signed GetBucketLocation). Without it every SigV4 request fails.
2. **Phone-only capture route.** `bot_runtime` lazily imports the parked
   phone-only `capture_route` module per incoming message; absent in this
   package, it killed every message with ModuleNotFoundError while
   `/status` showed `bot_error: null`. Fixed by the adapter no-op
   (commit fed584b). Lesson: `/status` green is not proof of serving.
3. **Dependency closure.** The first accepted chat crashed on missing
   `bot.py` / `delivery_bot.py` (fixed in commit 27a81c5, guarded by
   `tests/test_depclosure.py`). Health checks missed this too: only a
   real conversation round-trip proves serving.
4. **Rolling updates crash-loop.** The new pod waits on the old pod's DB
   lease, which the old pod never releases while alive. Updates must be
   stop-then-start (recreate); the 90s lease TTL bounds the gap. A
   rolloutReady-style check counts the OLD Ready pod - after any update,
   verify the serving image tag, not just readiness.

## Post-deploy verification checklist

- `GET https://<app-host>/healthz` -> `{"ok": true}`; `/status` shows
  `running: true`, `db: "up"`, live `served_chats` count.
- Logs show `bot account created` (fresh identity) or a restore line,
  then `checkpoint stored`.
- Round-trip: message the bot, get a reply; add it to a group, get the
  list served.
- Publish probe: drop a synthetic `.xdc` into `<prefix>/incoming/` in the
  bucket; a JSON result lands in `<prefix>/results/`. Remember publish
  fans out to EVERY served chat.
- Billing: the project's `/usage` lines exist and match the estimate.

## Teardown (verified 2026-11-23)

1. `neo_delete_service`, `neo_delete_database`, `neo_delete_bucket`.
   Each is safe-by-default: the first call returns a server-side
   confirmation and deletes NOTHING; approve with
   `neo_confirm_action(confirmationId, "approve")` and re-call with the
   confirmationId. Deletes converge: poll until inventory GETs 404.
2. Delete the now-empty **project shell manually in the portal** - the
   API/CLI did not remove it in the trial.
3. Verify the project's `/usage` lines stop and later samples stay flat
   (trial: flat across post-deletion samples, all four lines gone).
4. The saved transfer code is the only way back to the old bot identity.
   Destroy it only when that identity is abandoned for good.

## State model reminder

Bot identity and op histories live as encrypted checkpoint bundles in the
Postgres database (last 3 kept); blobs (incoming `.xdc`, results, invite
artifacts) live in the bucket. The container's work dir is ephemeral.
Full env contract and architecture: [README.md](README.md).

## Which deployment are you restoring?

The paid NEO trial above was torn down, not merely stopped. Its old IDs and
hostname are historical: do not try to restart a deleted app. The public
[demo](../demo-bot/README.md) is a separate, limited daily GitHub Actions bot,
not the full hosted service and not the owner's Android account. Its state
and key cannot restore the hosted bot. These instructions are a recovery
recipe, not approval to create paid resources.

### Rebuild from scratch after deletion

1. Get approval for the current full NEO estimate before creating or starting
   billable resources. Use the official portal to supply current credentials
   securely; never commit keys or connection strings. The November 2026 rate
   above is a historical estimate, not a current quote.
2. Clone this repository and check out the intended tested commit. Install the pinned dependencies with
   `python3 -m pip install -r server/requirements.txt`, then run
   `python3 -m unittest discover -s server/tests -v`. Review `server/Dockerfile`
   and the environment contract in `server/README.md`.
3. Follow Procedure steps 1-8 with a **new dedicated project**, database,
   private bucket and app, recording the new IDs and HTTPS domain privately.
   Use `server/` as the build context and its Dockerfile, port 8080, one
   fixed replica, 0.5 CPU and 512Mi RAM. Recreate bindings and secret variables;
   deleted project tokens may not work on the replacement.
4. To preserve identity, restore a retained encrypted checkpoint and blob
   backup plus the matching transfer code before starting the bot. The code
   alone is not a backup. If the old database/bucket and all backups were
   deleted, the old identity and histories cannot be recovered: deliberately
   provision a new account and replace any invite only after a real round trip.
5. Apply every gotcha above, especially `JIN_S3_SIGNING_REGION` and a
   stop-then-start update. Run the full post-deploy checklist. Recheck the
   serving image, replicas and usage after every deployment.

### Restart an existing retained service

First verify the project, app, database and bucket still exist. Keep their
bindings, matching transfer code and checkpoint data. Starting retained
resources resumes charges and requires approval of the current costs. Stop
the app before an update, wait for all old pods to terminate and the database
lease to expire (up to 90 seconds), then start exactly one replica. Do not run
two independent instances with the same account. Follow the post-deploy
checklist, including the real message round trip and an image-tag check.

### State check before and after restart

Record the latest checkpoint time, bot identity, served-chat count and image
tag in private operations notes. Verify `/healthz` and `/status`, but also send
a harmless synthetic message from an existing served chat and confirm its
previous list/history remains. Verify a new checkpoint is stored. Never print
transfer codes, relay credentials, decrypted checkpoints or personal list
contents into build logs or this public repository. If restore fails, stop
rather than silently creating a replacement bot.

### Pause versus teardown

Stopping only app compute does not necessarily stop database or bucket charges.
For a temporary pause, check each retained resource's current billing and
compare usage samples. For full removal, export and privately retain encrypted
checkpoint/blob backups and the matching transfer code if future recovery is
wanted, then follow Teardown above. Check app, database and bucket inventory,
remove the empty project in the portal if required, and compare later usage
samples. Remove stale public invites and endpoints. Deleting resources without
backups is irreversible even if the transfer code is still saved.
