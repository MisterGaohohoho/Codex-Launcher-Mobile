# Companion protocol

## Transport

Pair with the original v1 QR payload: a local HTTPS IPv4 endpoint, exact SHA-256 leaf certificate fingerprint, and one-use token. Submit `POST /api/v1/pair`; subsequent requests use the returned token in `Authorization: Bearer …`. PC redirects and browser Origin requests are rejected. Revocation terminates open event streams.

Pairing stays at version **1**. New capabilities are advertised in the snapshot. All v2 endpoints require a full-control device.

| Method | Existing endpoint | Purpose |
| --- | --- | --- |
| GET | `/api/v1/snapshot` | Run, workers, configuration summary, recent logs |
| GET | `/api/v1/workers/{id}?runId=…&attempt=…` | Request/response detail |
| POST | `/api/v1/start` | Start saved configuration |
| POST | `/api/v1/stop` | Stop the identified current run |
| PATCH | `/api/v1/keep-alive` | Change current or default keep-alive |

The snapshot adds `configurationRevision`, `capabilities`, and the current PC process's `commandEpoch`. V1 commands carry a UUID `requestId` and expected run identity. With PC 2.8.0-beta.2, `configuration.model` resolves the configured CLI/HTTP model and prioritizes the model recorded for an active request. Unknown script/default models are explicitly labelled instead of guessed.

## Administrator queries

Send a JSON object to `POST /api/v2/query/{resource}`.

| Resource | Input |
| --- | --- |
| `configuration` | `{}` |
| `workbench-capabilities`, `workbench-settings`, `workbench-models` | `{}` |
| `workbench-threads` | Optional `search`, `archived`, `cursor` |
| `workbench-thread` | `threadId`, optional history `cursor`; response includes `backendScopeId` and the available `thread.model` |
| `workbench-message` | `threadId`, stable `messageId`; returns persistent submission state and any confirmed reply |
| `workbench-attachment-storage` | `{}`; attachment usage and unreferenced temporary storage |
| `manager` | Allowlisted `operation` and `input` |
| `usage` | Optional ledger filters, offset, limit |
| `maintenance` | Optional session search, archive/storage filters, offset, limit, refresh |
| `billing`, `credentials`, `schedule`, `usage-sync`, `usage-reprice` | `{}` |

Configuration responses contain `revision`, `value`, and `secretFields`. Masked secrets must be omitted from a patch to preserve them. Vault credentials are not returned by configuration reads.

## Administrator commands

Send `POST /api/v2/command`:

```json
{
  "requestId": "00000000-0000-4000-8000-000000000001",
  "commandEpoch": "epoch-from-latest-snapshot",
  "issuedAt": 1795305600000,
  "action": "configuration.patch",
  "input": {"revision": "revision-from-read", "patch": {"keepAlive": true}}
}
```

Use the snapshot's `serverTime`, converted to Unix milliseconds, for `issuedAt`; do not depend on the phone's clock. Commands are bounded and serialized. The running PC service retains up to 256 receipts for 15 minutes, including when the HTTP client disconnects. Retry uncertain requests with the **same** identifier/body. Different bodies with the same identifier are rejected. A different `commandEpoch`, an expired request, or an evicted receipt is rejected with HTTP 409 instead of being executed again. Inspect actual state before issuing a replacement. The client holds unconfirmed administrative commands in memory and blocks new mutations until the previous result is checked. Workbench messages and attachment drafts are persisted per PC and conversation; recovered submissions are checked against persistent message status or history before another send is allowed.

Configuration updates atomically compare revisions. HTTP 409 requires re-reading and reconciling the current configuration. Saved execution settings apply on the next run; v1 keep-alive changes can affect the current run.

| Workbench action | Input |
| --- | --- |
| `workbench.create` | PC `cwd`, optional `model`, optional `name` |
| `workbench.fork`, `workbench.attach` | `threadId` |
| `workbench.send` | `threadId`, `text`, UUID `messageId`, optional `attachmentIds` |
| `workbench.steer` | `threadId`, expected `turnId`, `text`, UUID `messageId`, optional `attachmentIds` |
| `workbench.interrupt` | `threadId`, expected `turnId` |
| `workbench.answer` | Original RPC `id`, approval `epoch`, typed `answer` |
| `workbench.rename` | `threadId`, `name` |
| `workbench.archive` | `threadId` |
| `workbench.configure` | `connectionMode`, `workDir`, `codexHome`, `executable`, `socketPath` |
| `workbench.attachments-cleanup` | `{}`; explicitly removes only unreferenced temporary uploads |

The gateway never directly executes a phone-provided shell command. Codex interprets development prompts and retains its permission policy. Unsupported App Server methods are not exposed. Only a live approval can be answered; persistent approval bypasses are not accepted.

Attachments require the `workbench.attachments.v1` snapshot capability and versioned policy in `workbench-capabilities.attachments`. Upload and message submission are separate operations. Empty `text` is valid only with completed, authorized attachments. [Attachment protocol](attachments.md) specifies transport, scope, limits, recovery, content support, and history association.

Other actions: `manager`, `credentials.set`, `credentials.delete`, `schedule.install`, `schedule.remove`, `health.check`, `billing.saveRule`, `billing.deleteRule`, `billing.savePrice`, `billing.deletePrice`, `billing.saveGroup`, `usage.sync`, `usage.cancelSync`, `usage.reprice`, and `usage.cancelReprice`. Desktop validation and idle requirements apply. Credential kinds are `http`, `smtp`, `resend`, `cloud`, `serverChan`, and `proxy`.

`maintenance.prepare` accepts `action` (`archive`, `delete`, `export`, or `cleanMissing`), 1–100 selected `ids`, the catalog `revision`, and an optional PC `destination` for export. It returns a checked job with affected names/counts. Execution is a separate `manager` command with `operation: "sessions.jobs.start"` and the job `id`, after user confirmation. Pause, cancel, resume, and record removal use the existing desktop job system.

## Streaming

Use authenticated `GET /api/v2/events?epoch=…&since=…`. SSE `workbench` events contain `{ "workbench": { "epoch", "cursor", "reset", "events", "approvals", "connected" } }`. This Codex connection epoch is independent of the PC command epoch.

Advance the cursor after processing. A reset indicates a replay gap or backend restart: reload selected-thread history. The bounded journal contains public replies, tool execution, plans, diffs, approvals, and completion. Internal model reasoning is excluded.

History is paginated. New servers return `itemsPage` with 40 descending item entries (`turnId`, `item`) and `nextCursor`, plus lightweight turn metadata in `turnsPage`. Reverse items for chronological display. Older Codex versions fall back to full paginated turns. Thread detail includes `activeTurnId`, including a turn that is still starting. `control` is `managed`, `attach`, or `readOnly`; history timestamps do not imply live controllability. On stream failure, reconnect with backoff. Independently running read-only histories are polled even while the managed stream is connected. Disable mutations while authenticated PC snapshots are stale.
