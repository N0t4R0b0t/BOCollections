# Documentation

- **[product-overview.md](./product-overview.md)** — the *why*: what BOCollections is for, and the
  reasoning behind bulk-scan mode, thrifting mode, the catalogue/collections split, and export/
  backups. Start here if you're new to the app.
- **[architecture.md](./architecture.md)** — the *how*: system diagram, data model, backend
  patterns (vision failover, barcode waterfall, async analysis), frontend structure.
- **[deployment.md](./deployment.md)** — how a commit becomes a running instance: the release
  pipeline, the Proxmox VE LXC install, full env-var configuration reference, backups.
- **[specs/](./specs)** — pre-implementation design docs written to pitch and plan specific
  features before they were built. Historical/proposal-stage record, not kept in sync with the
  shipped app the way the three docs above are.
