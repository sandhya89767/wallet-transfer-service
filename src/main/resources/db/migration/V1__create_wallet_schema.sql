CREATE TABLE wallets (
    id UUID PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL UNIQUE,
    balance_paise BIGINT NOT NULL CHECK (balance_paise >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE transfers (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(200) NOT NULL UNIQUE,
    from_wallet_id UUID NOT NULL REFERENCES wallets(id),
    to_wallet_id UUID NOT NULL REFERENCES wallets(id),
    amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
    status VARCHAR(24) NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'DECLINED')),
    failure_reason VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (from_wallet_id <> to_wallet_id)
);

CREATE INDEX transfers_from_wallet_idx ON transfers (from_wallet_id, created_at DESC);
CREATE INDEX transfers_to_wallet_idx ON transfers (to_wallet_id, created_at DESC);