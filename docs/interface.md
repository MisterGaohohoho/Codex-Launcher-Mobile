# Mobile interface

The 0.5 interface is implemented with native Jetpack Compose components. Its neutral palette and floating composer take visual inspiration from [Cherry Studio Mobile](https://github.com/CherryHQ/cherry-studio-app). Its searchable, swipeable conversation sidebar takes interaction inspiration from [Galaxies-dev's ChatGPT example](https://github.com/Galaxies-dev/chatgpt-clone-react-native). No application code or assets from those repositories are included.

## Navigation

- **Overview** shows live PC and ANY status, the start/stop action, recent development conversations, and configuration shortcuts.
- **Workbench** groups conversations by project. The sidebar is also available while reading or composing a message. It supports search, selection, and long-press continuation choices.
- **Run** separates live controls, individual requests, and logs. Keep-alive changes on this page apply to the current run.
- **Settings** groups PC administration, usage and billing, automation, appearance, and APK updates into disclosure rows.

The compact bottom navigation appears on the four main pages. Conversation and settings detail pages use the available height for their content. Android Back closes a sidebar or editor before leaving the current screen.

Tap the computer name, sidebar footer, or **Settings → My computers** to switch PCs. The sheet shows separate run/connectivity summaries, supports local names and alternate addresses, and adds or removes profiles. Switching dismisses old command confirmations and keeps each PC's conversation state separate. See [computer switching](computers.md).

## Conversation controls

Conversations open directly to messages. Execution records and file changes are available in the three-dot session menu, with a compact return-to-chat action. The subtitle prioritizes the model, then the selected computer and project. Conversation loading is separate from PC connectivity: cached content remains visible during refresh, and uncached threads have loading, retry, and return actions.

Tap the conversation title or choose Rename from the session menu. The sidebar's long-press sheet also offers Rename, and the new-session sheet accepts an optional name. Pull down at the top to load earlier history, or refresh when no earlier page remains; the explicit Load earlier button is retained. Prepending history preserves the reader's scroll anchor.

The continuation sheet always distinguishes **Continue original session** and **Create a branch**.

- The original option is enabled only for the gateway's `managed` and `attach` control states. It calls `workbench.attach` with the selected thread ID. Subsequent messages use that same ID.
- An idle conversation uses `workbench.send`. An active turn uses `workbench.steer` with its expected turn ID.
- A branch calls `workbench.fork`. The gateway returns a separate thread ID with inherited history. Future messages are independent; project files are still shared. This is not a Git branch or worktree.
- Independently owned history stays read-only. The original option explains that a compatible shared backend must be connected, and links to its connection settings. The app never silently substitutes a branch for the original option.
- A successful attach selects the requested original session, including when it was chosen from the sidebar while a different conversation was open.

The composer's plus button offers Photos, Camera, and Files. Selected items appear above the input; tapping opens a preview or file details. Sending freezes the destination and content, so switching conversations during upload cannot redirect the message. A changed active turn requires explicit confirmation before changing from steering to a new turn. See [attachment behavior](attachments.md).

Unsent text/file drafts and pending submissions are stored privately by PC fingerprint, backend scope, and thread. Delivery reconciliation, interruption, approvals, compaction, retries, and the PC's permission policy remain in effect.

The Run control card displays the resolved CLI/HTTP model supplied by the PC. Active requests retain their recorded model when saved defaults change. When a wrapper script or unset default prevents reliable resolution, the PC returns an explicit unavailable/default label.

## Configuration

Configuration pages show grouped names and current values. Boolean values use switches, enumerated values use selection sheets, numeric values use staged text entry and steppers, and objects and collections use nested editors. Free-form fields are shown only while editing that value.

Editors stage valid changes locally. Cancel leaves the previous value intact. Non-finite numbers cannot be committed. The main configuration save sends only the changed fields together with the PC revision; the PC performs validation and conflict checks. Reloading with local changes requires an explicit discard action.

Secret summaries never display the secret. Existing masked credentials are retained when unchanged; deletion remains an explicit operation in Credentials.

## Appearance and accessibility

Light, dark, and system themes use one neutral palette, consistent rounded surfaces, and Android's system fonts. The original Launcher icon and pairing transport are retained. Drawer and sheet motion, short page transitions, segmented selection animation, and message size transitions provide feedback using native Compose animation.

Settings values stack below labels at larger font scales. Main screens scroll without moving fixed navigation; the conversation composer responds to keyboard insets. Interactive states distinguish loading, disconnection, errors, read-only history, and pending command outcomes.

## Verification

Native Compose interaction tests cover both continuation choices, original thread identity, active-turn steering, sidebar search, separate drafts, configuration precision and cancellation, permissions, pairing, request details, approvals, and interrupted connectivity.

Screenshots in `docs/screenshots` are rendered from the native UI using synthetic fixtures. The renderer includes visible Android dialog windows so configuration sheets and continuation choices can be reviewed. They are not photographs from a physical phone.
