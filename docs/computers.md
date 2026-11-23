# Multiple computers and connectivity

Open **My computers** by tapping the computer name above a main page, the computer footer in the conversation sidebar, or its Settings row. Add each PC with its own QR code. Up to 16 profiles are supported. The certificate fingerprint identifies a PC even when its IP address changes. Pairing the same certificate again renews that profile and preserves its local nickname and known addresses.

The sheet checks other PCs when opened or refreshed, with at most two checks in parallel. Inactive PCs show when their status was last read. An unreachable PC does not disconnect another PC. Select a row to change the active destination; the selected computer also appears in conversation subtitles. AI and ANY tasks continue on their respective PCs when the phone switches away.

Credentials and the active selection are encrypted using the existing Android Keystore key. The old single-PC record migrates in one preferences commit. If saving a switch fails, the current connection remains usable. Removing a profile only removes this phone's saved authorization; it does not stop tasks or erase message drafts. Revoke the phone from that PC to invalidate its server-side token.

Configuration, conversation caches, pending command outcomes, and outgoing-message records are scoped to each PC. Text/file drafts retain the PC fingerprint, backend scope, and thread ID. Switching pauses and joins active transfers and result checks, cancels old reads, and closes the previous client. A command being submitted must finish before switching. An uncertain command stays with its PC and is never automatically resent. Cached history is revalidated against the selected backend scope. Old confirmations are dismissed and UI callbacks retain their original PC identity.

## Connection paths

Desktop 2.8.0-beta.3 adds an **Automatically use local networks and Tailscale** option. It listens on actual private, link-local, or Tailscale IPv4 interfaces and checks for changes every 15 seconds. Selecting loopback remains local-only. Disabling the option restricts the service to its selected interface. Public addresses and wildcard binds are rejected.

The original version-1 QR fields stay unchanged. Authenticated pairing responses and snapshots may add `connectionEndpoints`; snapshots advertise `connection.endpoints.v1`. Older apps ignore the response additions and can still pair. Older PCs work with the profile switcher but do not advertise alternatives or add local listeners.

The phone considers only the paired endpoint, explicitly entered alternatives, and addresses returned by that authenticated PC. It does not scan the network. Every TLS handshake verifies the same pinned certificate before authorization is sent. Redirects, public endpoints, and arbitrary hostnames are rejected. A nearby subnet is preferred; up to four candidates race with staggered starts and short timeouts. Failed alternatives cool down for 30 seconds. The successful route is used for later requests.

If the saved Tailscale address is already unreachable, the phone cannot learn a LAN address over that broken connection. Update the PC, then enter an available local address in the profile editor or scan a QR displaying that address. The same certificate is still required. Different networks still need working Tailscale access. Routing cannot bypass firewalls, hotspot isolation, or an unavailable PC.

## Loading and recovery

Snapshot requests and event streams use separate client queues. The desktop reserves snapshot admission separately from ordinary queries. Slow history or attachment transfers therefore do not by themselves queue the heartbeat.

Only read queries retry automatically, once, after a quick transient transport failure or HTTP 408/429/502/503/504. Authorization errors, TLS failures, and commands do not retry automatically. Polling backs off after consecutive failures; explicit Refresh restarts it. A failed heartbeat retains readable cached content. Mutation controls still require an authenticated snapshot no more than eight seconds old.

Conversation JSON and history parsing run off the UI thread. Recently viewed conversations can render from memory before refreshing. An uncached conversation has its own loading screen, return action, and retry action; slow history does not by itself label the PC disconnected.

Automated tests use synthetic PCs, local TLS sockets, encrypted migration fixtures, and native Compose rendering. Physical hotspot access, VPN background behavior, and signed APK upgrades still require verification on the target devices.
