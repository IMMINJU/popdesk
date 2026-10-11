# Upgrade samples

One folder for each released version, with the three files `make-sample.py` wrote on the day of that
release (see `../README.md`). They are the same files that are attached to the GitHub release.

| Folder | Made by |
|---|---|
| `0.1.0/` | the image in `0.1.0/sample.image`, on 2026-10-10 |
| `0.2.0/` | the image in `0.2.0/sample.image`, on 2026-10-12. The first with a suspended account |

The upgrade tests restore a sample and start the new image on it. The accounts and passwords in a
sample are test data made by `make-sample.py`; they are no secrets.
