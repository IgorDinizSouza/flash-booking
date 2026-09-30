CREATE TABLE reservations (
    id         UUID PRIMARY KEY,                       -- gerado na aplicacao
    event_id   UUID        NOT NULL REFERENCES events(id),
    quantity   INTEGER     NOT NULL,
    status     VARCHAR(20) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_reservations_quantity CHECK (quantity > 0),
    CONSTRAINT ck_reservations_status   CHECK (status IN ('PENDING','CANCELLED','EXPIRED'))
);

CREATE INDEX idx_reservations_pending_expiration
    ON reservations (expires_at) WHERE status = 'PENDING';
