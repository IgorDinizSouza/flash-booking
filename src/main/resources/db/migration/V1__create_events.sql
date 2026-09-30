CREATE TABLE events (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name           VARCHAR(150) NOT NULL,
    total_capacity INTEGER      NOT NULL,
    available      INTEGER      NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_events_capacity  CHECK (total_capacity > 0),
    CONSTRAINT ck_events_available CHECK (available >= 0 AND available <= total_capacity)
);
