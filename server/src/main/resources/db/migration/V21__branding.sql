-- The branding of the installation (BRAND-1 to BRAND-5). Slice 9 design section 1.

-- One row (id 1): the display name, the accent color as #RRGGBB in capitals or null for the app's own colors,
-- and a version that goes up when either changes. changed_by is the admin who saved it, null for popdesk itself
-- (the default row below, as version 1 of sla_policy in V18). Logos do not change the version.
CREATE TABLE branding (
    id smallint PRIMARY KEY CHECK (id = 1),
    tenant_id bigint NOT NULL,
    display_name text NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 50),
    accent text CHECK (accent ~ '^#[0-9A-F]{6}$'),
    version bigint NOT NULL CHECK (version >= 1),
    changed_by bigint REFERENCES account (id),
    changed_at timestamptz NOT NULL
);

INSERT INTO branding (id, tenant_id, display_name, accent, version, changed_by, changed_at)
VALUES (1, 1, 'popdesk', NULL, 1, NULL, now());

-- At most one logo per theme: the PNG popdesk made from the upload, never the uploaded file, its SHA-256 as
-- lower-case hex, and its size within 512 by 128 px.
CREATE TABLE branding_logo (
    theme text PRIMARY KEY CHECK (theme IN ('LIGHT', 'DARK')),
    tenant_id bigint NOT NULL,
    png bytea NOT NULL,
    sha256 text NOT NULL,
    width integer NOT NULL CHECK (width BETWEEN 1 AND 512),
    height integer NOT NULL CHECK (height BETWEEN 1 AND 128),
    changed_by bigint NOT NULL REFERENCES account (id),
    changed_at timestamptz NOT NULL
);
