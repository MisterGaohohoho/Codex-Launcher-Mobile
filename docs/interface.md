# Mobile interface

The 0.3 interface is implemented with native Jetpack Compose components. Its neutral palette and floating composer take visual inspiration from [Cherry Studio Mobile](https://github.com/CherryHQ/cherry-studio-app). Its searchable, swipeable conversation sidebar takes interaction inspiration from [Galaxies' ChatGPT example](https://github.com/Galaxies-dev/chatgpt-clone-react-native). No application code or assets from those repositories are included.

## Navigation

- **Overview** shows live PC and ANY status, the start/stop action, recent development conversations, and configuration shortcuts.
- **Workbench** groups conversations by project. The sidebar is also available while reading or composing a message. It supports search, selection, and long-press continuation choices.
- **Run** separates live controls, individual requests, and logs. Keep-alive changes on this page apply to the current run.
- **Settings** groups PC administration, usage and billing, automation, appearance, and APK updates into disclosure rows.

The compact bottom navigation appears on the four main pages. Conversation and settings detail pages use the available height for their content. Android Back closes a sidebar or editor before leaving the current screen.

## Conversation controls

The continuation sheet always distinguishes **Continue original session** and **Create a branch**.

- The original option is enabled only for the gateway's `managed` and `attach` control states. It calls `workbench.attach` with the selected thread ID. Subsequent messages use that same ID.
- An idle conversation uses `workbench.send`. An active turn uses `workbench.steer` with its expected turn ID.
- A branch calls `workbench.fork`. The gateway returns a separate thread ID with inherited history. Future messages are independent; project files are still shared. This is not a Git branch or worktree.
- Independently owned history stays read-only. The original option explains that a compatible shared backend must be connected, and links to its connection settings. The app never silently substitutes a branch for the original option.
- A successful attach selects the requested original session, including when it was chosen from the sidebar while a different conversation was open.

Unsent composer drafts are stored separately for each visited conversation in Compose saved state. Delivery reconciliation, command receipts, interruption, approvals, compaction, retries, and the PC's permission policy continue to use the existing protocol.

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
