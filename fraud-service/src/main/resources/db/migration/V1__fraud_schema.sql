CREATE TABLE fraud_alerts (id UUID PRIMARY KEY, transfer_id UUID NOT NULL UNIQUE, account_id UUID NOT NULL, score INTEGER NOT NULL, reasons TEXT NOT NULL, ai_explanation TEXT, status VARCHAR(16) NOT NULL DEFAULT 'NEW', reviewed_by UUID, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE processed_events (event_id UUID PRIMARY KEY, processed_at TIMESTAMPTZ NOT NULL DEFAULT now());
