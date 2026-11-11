CREATE TABLE staff_audit_log (
    id UUID PRIMARY KEY,
    actor_user_id UUID NOT NULL,
    subject_user_id UUID NOT NULL,
    action VARCHAR(64) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX staff_audit_created_idx ON staff_audit_log(created_at DESC);
