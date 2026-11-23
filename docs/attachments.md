# Attachments (M1)

Launcher Mobile 0.4.0 and desktop 2.8.0-beta.2 add image/file messages to controllable Codex conversations. The PC must be online over the existing LAN or Tailscale connection. The public update domain continues to distribute APK updates; it does not receive conversation files.

## User flow

Use the plus button beside the composer to select photos, take a photo, or select files. The app copies content into private storage, reads its actual size, and calculates SHA-256. A phone content URI is never treated as a PC path. Camera output uses a separate restricted FileProvider.

Selected items appear above the input. You can remove an item, preview an image, inspect file metadata, and send without text. A new-session sheet can collect files before a thread exists; creation moves that draft to the returned thread, ready to send.

Sending freezes the PC fingerprint, backend scope, thread, message UUID, text, and selected attachment list. Uploads run two at a time, with sequential chunks for each file. Leaving the app pauses uploads. Resume and discard are explicit actions; unresolved message submissions cannot be discarded as if they had never been sent. Switching the visible conversation does not change an upload's destination.

The app verifies the original backend before uploading and checks the thread and active turn again before submission. If an active turn has ended or another turn has started, it preserves the files and asks the user to confirm the current send/steer mode. It never silently turns an in-progress steering instruction into a new task.

## Supported content and limits

| Item | Default |
| --- | --- |
| Attachments per message | 10 |
| Static JPEG/PNG/WebP | 20 MiB each |
| Other files | 50 MiB each |
| Combined message attachments | 100 MiB |
| Upload chunk | 1 MiB |
| Concurrent transfers per device | 2 |
| Image decoding | 40 million pixels, each edge at most 16,384 pixels |
| Inline UTF-8 text/code/logs | Up to 32 KiB combined per message |
| PC attachment quota | 2 GiB |
| Minimum PC free disk reserve | 1 GiB |
| Unreferenced temporary retention | 24 hours after last activity; cleanup is opportunistic or explicit |
| Private phone draft files | 256 MiB |

The PC advertises its actual bounded policy. Smaller limits take precedence. Images are validated from their bytes, including animation and decoding limits. HEIC/HEIF conversion uses Android's decoder where available; unsupported conversions report an error. GIF can be uploaded as an ordinary file; animation analysis is not provided.

Validated images become Codex `localImage` inputs. Image analysis still depends on the model and provider; `imageInput: "unknown"` does not claim universal vision support. Text files include clear reference boundaries and a truncation notice when needed. Original files remain on the PC.

Binary files, PDF, Office documents, and archives are uploaded with an explicit unsupported/not-parsed status. The AI can request the original file through its existing PC permissions, but this release does not implement document parsers, archive extraction, or OCR. No file is executed by the upload service. Content sent into a conversation is processed by the currently configured AI provider.

## Negotiation and endpoints

The snapshot retains its string-array `capabilities` field and adds `workbench.attachments.v1`. `workbench-capabilities` returns `backendScopeId` and `attachments`, including `protocolVersion: 1`, `upload`, `resumable`, limits, `chunkSizeBytes`, `inputTypes`, `imageInput`, and `parsers`.

All endpoints require the same exact certificate pin, device bearer token, and full-control permission as v2 commands. Local PC paths and credentials are not sent by the phone. Redirects are rejected. Ordinary command bodies retain their 128 KiB limit; the chunk route alone accepts 1 MiB.

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/v2/attachments/uploads` | Initialize or recover an upload |
| GET | `/api/v2/attachments/uploads/{uploadId}` | Read acknowledged offset and completion |
| PUT | `/api/v2/attachments/uploads/{uploadId}/chunks/{index}` | Write a binary chunk |
| POST | `/api/v2/attachments/uploads/{uploadId}/complete` | Verify and finalize |
| DELETE | `/api/v2/attachments/uploads/{uploadId}` | Cancel an unreferenced upload |
| GET | `/api/v2/attachments/{attachmentId}?threadId=…` | Authorized metadata |
| GET | `/api/v2/attachments/{attachmentId}/content?threadId=…` | Original bytes |
| GET | `/api/v2/attachments/{attachmentId}/preview?threadId=…` | Bounded, re-encoded image preview |

Initialization accepts `clientUploadId`, `threadId`, `messageId`, display-only `fileName`, `mediaType`, real byte `size`, and lowercase `sha256`. The PC resolves the backend scope. Device ID plus `clientUploadId` is a persistent idempotency key: identical metadata returns the same upload, conflicting metadata returns 409.

A chunk uses `Content-Type: application/octet-stream`, `Content-Range: bytes START-END/TOTAL`, and `X-Chunk-SHA256`. The PC acknowledges only flushed bytes and persisted offsets. Duplicate chunks must match the original range and hash. After a lost reply, the app queries the acknowledged offset before continuing. Completion validates full length/hash/type and returns a stable `att_` UUID object, including after a PC restart between object rename and index commit.

Downloads support one bounded byte range of at most 1 MiB; full downloads remain bounded by the attachment's allowed size. Authorization is checked while transfers stream so revocation terminates ongoing content access.

Errors include `code`, `message`, `details`, and `retryable`. Offsets are authoritative on the PC. Authorization, quota, disk, content, and expired-upload failures retain the phone draft. An explicit resume can replace a missing or expired transfer while preserving the same frozen message and private file. Credentials never travel through the public update Worker.

## Submission, history, and storage

`workbench.send` and `workbench.steer` add `attachmentIds` to the existing input; `text` may be empty. New uploads are tied to the original thread/message and are checked before submission. `steer` retains the expected `turnId`.

The PC persists message/attachment association before calling Codex. `workbench-message` accepts the original `threadId` and `messageId`, returning `state` (`sent`, `failed`, or `unknown`), any `reply`, and attachment metadata. A timed-out or restarted submission remains unknown until confirmed from stored results or history. A message UUID is an association key, not a guarantee that every Codex backend implements exactly-once submission.

For older PCs without persistent message status, manual verification may reuse the exact request only within the same PC process epoch and receipt window. It never replaces an ambiguous operation with a new request ID.

History carries `displayText`, `clientId`, and registered `attachments` metadata. On backends that omit client IDs, a stable attachment-message marker restores the association. Branches inherit access only when an actual copied history item matches the registered identity, not merely identical text. External Desktop/CLI image paths never become arbitrary phone download URLs.

Temporary files are private to the uploading device. Submitted references are accessible to full-control devices authorized for the corresponding backend/thread. Referenced or unresolved-message objects are retained. Desktop storage controls show usage and clean unreferenced temporary uploads; this release does not offer deletion of historically referenced objects.

The original pairing protocol, APK signing key, application ID, and Cloudflare update infrastructure are unchanged. Physical phone camera, vendor background policy, and installation/upgrade checks should be completed on the intended device in addition to automated tests.
