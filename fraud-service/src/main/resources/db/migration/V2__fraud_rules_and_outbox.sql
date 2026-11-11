CREATE TABLE observed_transfers (
    event_id UUID PRIMARY KEY,
    source_account_id UUID NOT NULL,
    destination_account_id UUID NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);
ALTER TABLE fraud_alerts ADD COLUMN user_id UUID;
CREATE INDEX observed_source_time_idx ON observed_transfers(source_account_id, occurred_at DESC);
CREATE TABLE known_recipients (
    source_account_id UUID NOT NULL,
    destination_account_id UUID NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(source_account_id,destination_account_id)
);
CREATE TABLE fraud_outbox_events (
    id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    payload JSONB NOT NULL,
    published BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
