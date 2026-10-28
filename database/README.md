# Database

The migrations live with the API so they ship and run with it:
`backend/employee-monitor-api/src/main/resources/db/migration/`

| Migration | Contents |
|---|---|
| `V1__core_schema.sql` | roles, permissions, departments, employees, users, refresh_tokens, manager_assignments, monitoring_policies, devices, device_tokens, enrollment_tokens, agent_versions, audit_logs |
| `V2__seed_rbac_and_default_policy.sql` | the five roles, permissions, role → permission grants, default monitoring policy |

Rules:
- Never edit a migration that has been applied anywhere. Add a new `V{n}__description.sql`.
- Hibernate runs with `ddl-auto: validate`, so the entities must match the migrations or the app will not start.

## Planned for event tables (Milestone 4+)

`session_events`, `idle_events`, `application_usage`, `website_usage`, `usb_events`, `network_events` and `zscaler_events` will be:

- **range-partitioned by month** on their timestamp column (`PARTITION BY RANGE (occurred_at)`), with partitions created ahead by a scheduled job
- indexed on `(employee_id, occurred_at)`, `(device_id, occurred_at)` and `(event_type, occurred_at)` per partition
- subject to retention: whole partitions older than the longest active `retentionDays` are dropped, which is cheaper and safer than row deletes
