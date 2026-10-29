-- =====================================================================
-- V11: Add the requireControlConsent flag to existing monitoring policies.
-- Remote control requires the employee's on-screen consent by default;
-- an admin may set this to false per device (a device override) to allow
-- unattended control. A visible "being controlled" banner is always shown
-- regardless of this flag. Backfill every stored policy so the field is
-- explicit rather than relying on the application-level default.
-- =====================================================================

UPDATE monitoring_policies
SET settings = jsonb_set(settings, '{requireControlConsent}', 'true', true)
WHERE NOT (settings ? 'requireControlConsent');
