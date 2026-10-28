# Agent deployment & auto-update (Milestone 7)

How the agent is packaged, installed so it has full monitoring fidelity, and updated with cryptographically signed
releases. For backend production deployment see [deployment.md](deployment.md).

## Why the agent runs in the user session

Idle/lock detection, foreground-application tracking and screen capture (screenshots and live view) require the
interactive desktop session — a Session-0 Windows Service cannot see the logged-in user's desktop (the limitation
noted since Milestone 2). The installer therefore runs the agent and its visible tray **in the user session via
logon scheduled tasks**. That is the per-session execution the earlier milestones deferred to M7, and it resolves
the limitation: idle/active, lock/unlock, foreground app, screenshots and live view all work correctly.

An always-on, presence-only alternative (`scripts/install-service.ps1`) installs the agent as a Session-0 service;
it keeps the device present and policy-synced but cannot report accurate idle/lock or capture the screen. Choose
the logon-task install for full fidelity.

## Installer: `EmployeeMonitorSetup.exe`

Built with Inno Setup from [agent/EmployeeMonitor.Installer/EmployeeMonitor.iss](../agent/EmployeeMonitor.Installer/EmployeeMonitor.iss).

```powershell
# Produces a self-contained build and compiles the installer (needs Inno Setup for the final step).
.\scripts\build-installer.ps1 -Version 1.0.0
# -> agent\EmployeeMonitor.Installer\Output\EmployeeMonitorSetup.exe
```

The installer: requires Windows 10+/x64 and admin; installs the self-contained binaries (no .NET prerequisite);
**shows the monitoring notice** ([monitoring-notice.txt](../agent/EmployeeMonitor.Installer/monitoring-notice.txt))
before install; prompts for the **enrollment token** and **backend URL**; stores them machine-wide; registers the
logon tasks for the agent and the tray; launches the tray; and provides a full uninstaller (no anti-removal
mechanisms). On first run the agent enrolls the device, downloads its policy and starts monitoring.

For environments without Inno Setup, `scripts/install-agent.ps1` (elevated) performs the same install directly from
source — publish, configure, show the notice, register the logon tasks, verify connectivity — and
`scripts/uninstall-agent.ps1` removes it. Code-sign `EmployeeMonitorSetup.exe` and the agent binaries with your
certificate (`signtool sign /fd SHA256 /tr <timestamp> ...`) before distribution.

## Enrollment

The installer takes an enrollment token from `POST /api/enrollment-tokens` (IT admin, limited uses + expiry,
optionally pre-assigned to an employee). First run exchanges it for a device credential stored with DPAPI
(machine scope). Re-running on the same hardware re-uses the device record and rotates the credential.

## Signed auto-update

Updates are cryptographically signed; the agent verifies **SHA-256 and an RSA signature** against an embedded
public key before installing, so an unsigned or tampered package is never applied.

```text
new-update-keypair.ps1 ─▶ public key  -> agent config (Agent:UpdatePublicKey, set by the installer)
                         private key  -> kept OFFLINE by the release owner
publish-update.ps1 ─ sign package (SHA-256 + RSA) ─▶ POST /api/agent-versions  (created unapproved)
admin approves ─▶ PUT /api/agent-versions/{id}/approval
agent (every few hours) ─ GET /api/agent/update?current=… ─▶ download ─▶ verify sha256 + signature
                        └─ stage verified package for the installer to apply; reject if verification fails
```

Operator steps:

```powershell
.\scripts\new-update-keypair.ps1 -OutDir .\keys          # once; configure agents with keys\agent-update-public.key
.\scripts\build-installer.ps1 -Version 1.2.0             # build the new EmployeeMonitorSetup.exe
.\scripts\publish-update.ps1 -Version 1.2.0 -PackagePath .\agent\EmployeeMonitor.Installer\Output\EmployeeMonitorSetup.exe
# then approve version 1.2.0 in the admin app
```

Only `AGENT_MANAGE` holders (SUPER_ADMIN, IT_ADMIN) can publish/approve; only approved versions strictly newer than
the agent's current version are offered; packages are served only to authenticated devices.

## Tests

- Backend `AgentUpdateIntegrationTest`: publish → approve → agent check/download, SHA-256 match, RSA signature
  valid for the bytes, tampered package fails, older/equal not offered, only AGENT_MANAGE may publish, duplicate
  version rejected.
- Agent `AgentUpdaterTests`: signature/SHA verification (valid, tampered, wrong hash, wrong key), staging a
  verified update, rejecting an invalid one.
- Live `scripts/update-smoke.ps1`: generates a key, publishes+approves a signed package, runs the **real agent**
  with the matching public key and confirms it downloads, verifies and stages the update.
