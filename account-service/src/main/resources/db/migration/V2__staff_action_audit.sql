CREATE TABLE staff_action_audit (
    id UUID PRIMARY KEY,
    actor_user_id UUID NOT NULL,
    account_id UUID NOT NULL REFERENCES accounts(id),
    action VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX staff_action_audit_created_idx ON staff_action_audit(created_at DESC);
