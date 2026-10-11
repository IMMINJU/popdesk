-- People (slice 1a): password reset tokens, profile photos, when a password last changed, and when an
-- invitation was cancelled. Only adds tables and columns; no row that exists is changed, so it runs on
-- the data of v0.1.0 as it is.

-- Owned by helpdesk (accounts). The reset link of an account, apart from invitations so that neither
-- token works as the other (S1A-AC-83). One row per account: a new link replaces the row.
CREATE TABLE password_reset (
    account_id bigint PRIMARY KEY REFERENCES account (id),
    tenant_id bigint NOT NULL,
    -- SHA-256 of the 32-byte token, as lower-case hex. Never the token (S1A-AC-08).
    token_hash text NOT NULL,
    -- When the link was made; a request within 15 minutes of it makes no new one (S1A-AC-12).
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    -- Set when the token set a password. The cleanup deletes used and expired rows (S1A-AC-15).
    used_at timestamptz,
    CONSTRAINT password_reset_token_hash_key UNIQUE (token_hash)
);

-- Owned by helpdesk (accounts). The profile photo of an account, always a PNG of at most 256 px a
-- side (D-53). Apart from account so that reading an account never loads the bytes.
CREATE TABLE account_photo (
    account_id bigint PRIMARY KEY REFERENCES account (id),
    tenant_id bigint NOT NULL,
    png bytea NOT NULL,
    size_bytes integer NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT account_photo_size_bytes_check CHECK (size_bytes > 0)
);

-- When the password last changed, by the account, a reset link, or an admin's reset. A session of a
-- sign-in from before it lets nobody in (S1A-AC-91). Null for a password that never changed.
ALTER TABLE account ADD COLUMN password_changed_at timestamptz;

-- When an admin cancelled the invitation (S1A-AC-42). Null for an invitation that was not cancelled.
ALTER TABLE invitation ADD COLUMN cancelled_at timestamptz;
