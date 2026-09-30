CREATE TABLE reservation_history (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reservation_id  UUID         NOT NULL REFERENCES reservations(id),
    event_id        UUID         NOT NULL,
    action          VARCHAR(20)  NOT NULL,
    previous_status VARCHAR(20)  NULL,
    new_status      VARCHAR(20)  NOT NULL,
    quantity        INTEGER      NOT NULL,
    reason          VARCHAR(30)  NOT NULL,
    correlation_id  VARCHAR(64)  NULL,
    instance_id     VARCHAR(30)  NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_history_action CHECK (action IN ('CREATED','CANCELLED','EXPIRED')),
    CONSTRAINT ck_history_new_status CHECK (new_status IN ('PENDING','CANCELLED','EXPIRED'))
);

CREATE INDEX idx_reservation_history_reservation
    ON reservation_history (reservation_id, created_at);
