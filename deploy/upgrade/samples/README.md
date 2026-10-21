# Upgrade samples

One folder for each released version, with the three files `make-sample.py` wrote on the day of that
release (see `../README.md`). They are the same files that are attached to the GitHub release.

| Folder | Made by |
|---|---|
| `0.1.0/` | the image in `0.1.0/sample.image`, on 2026-10-10 |
| `0.2.0/` | the image in `0.2.0/sample.image`, on 2026-10-12. The first with a suspended account |
| `0.3.0/` | the image in `0.3.0/sample.image`, on 2026-10-12. The first with a customer manager, a membership request, and issues with an assignee, history, and a filing key |
| `0.4.0/` | the image in `0.4.0/sample.image`, on 2026-10-13. The first with an edited comment, a deleted comment, a mention, and inbox items about issues |
| `0.5.0/` | the image in `0.5.0/sample.image`, on 2026-10-21. The first with a content image, labels, a due date, tasks, links, an issue in the trash and a purged one, SLA policy versions and alerts, branding with a logo, an anonymized account, a company export, an OIDC link, and received emails |

The upgrade tests restore a sample and start the new image on it. The accounts and passwords in a
sample are test data made by `make-sample.py`; they are no secrets.
