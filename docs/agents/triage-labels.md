# Triage Labels

The skills speak in terms of five canonical triage roles. This file maps those roles to the actual label strings used in this repo.

| Role in skills    | Label in our tracker | Meaning                                  |
| ----------------- | -------------------- | ---------------------------------------- |
| `needs-triage`    | `needs-triage`       | Maintainer needs to evaluate this issue  |
| `needs-info`      | `needs-info`         | Waiting on reporter for more information |
| `ready-for-agent` | `ready-for-agent`    | Fully specified, ready for an AFK agent  |
| `ready-for-human` | `ready-for-human`    | Requires human implementation            |
| `wontfix`         | `wontfix`            | Will not be actioned                     |
| —（非 triage）    | `done`               | Ticket 已实现并验收，不再需要分诊        |

When a skill mentions a role (e.g. "apply the AFK-ready triage label"), use the corresponding label string from this table.

## How labels are stored

This repo uses the **local-markdown** tracker, so there are no tracker-side labels. The label is written as a `Status:` line near the top of each issue file, for example:

```
**Status:** ready-for-agent
```

Edit the "Label in our tracker" column if the vocabulary ever changes.
