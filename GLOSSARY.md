# Homelab Notifications

A personal Android notification experience that turns homelab alerts into glanceable native notifications and richer in-app views.

## Language

**Alert**:
A message about an event or condition in the user's homelab that may need attention.
_Avoid_: Incident (alerts are separate initially; grouping is a future possibility)

**Homelab backend**:
The self-hosted service running in a user's homelab that accepts alerts, coordinates view generation, stores alert history, and serves alert data to their Android app. Each user runs their own backend.
_Avoid_: Pangolin (one possible network path, not the backend)

**Android app**:
The user's native Android client for receiving alert notifications and viewing their rich presentations.

**Network path**:
The user-selected means by which the app reaches the homelab backend, such as Pangolin, Tailscale, or the public internet.

**Rich view**:
An in-app presentation of an alert using structured visual components such as charts, tables, or diagrams.
