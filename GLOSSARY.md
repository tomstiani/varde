# Varde

A personal Android experience that turns homelab alerts into glanceable notifications and rich native views, without requiring each alert source to build its own interface.

## Language

**Alert**:
A message about an event or condition in the user's homelab that may need attention.
_Avoid_: Incident (an alert is not a grouped incident)

**Alert source**:
A homelab service or workflow that supplies alerts to Varde.
_Avoid_: Connection (the app's association with a backend, not the service or workflow supplying an alert)

**Original alert**:
The alert content supplied by an alert source, available to read independently of rich-view generation.
_Avoid_: Rich view (a presentation of the alert, not its original content)

**Homelab backend**:
The self-hosted service running in a user's homelab that accepts alerts, coordinates view generation, stores alert history, and serves alert data to their Android app. Each user runs their own backend.
_Avoid_: Pangolin (one possible network path, not the backend)

**Android app**:
The user's native Android client for receiving alert notifications and viewing their rich presentations.

**Connection**:
A named association between the Android app and a homelab backend. Each alert displayed in the app belongs to its source connection.

**Inbox**:
The combined list of alerts from the Android app's saved connections, with each alert labelled by its source connection.

**Alert notification**:
A glanceable phone notification announcing a homelab alert.
_Avoid_: Push message (a delivery mechanism, not the notification the person sees)

**Network path**:
The user-selected means by which the app reaches the homelab backend, such as Pangolin, Tailscale, or the public internet.

**Rich view**:
A native in-app presentation of an alert assembled from approved components and grounded in supplied data. It does not contain executable generated code or invented measurements.

**Approved component**:
A supported building block for a rich view, such as text, a metric, a chart, a table, or a simple flow.
