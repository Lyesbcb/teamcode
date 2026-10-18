CREATE TABLE scheduled_work (
    name VARCHAR(100) PRIMARY KEY,
    owner VARCHAR(36),
    available_at BIGINT NOT NULL DEFAULT 0,
    last_started_at BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE login_budget (
    id INT PRIMARY KEY,
    window_number BIGINT NOT NULL,
    attempts INT NOT NULL
);
INSERT INTO login_budget (id, window_number, attempts) VALUES (1, 0, 0);
CREATE TABLE gateway_totals (
    team_id VARCHAR(36) PRIMARY KEY,
    completed BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (team_id) REFERENCES teams(id)
);
INSERT INTO gateway_totals (team_id) SELECT id FROM teams;
CREATE INDEX team_active ON reservations (team_id, state, expires_at);
ALTER TABLE accounts ADD COLUMN refresh_pending BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE accounts ADD COLUMN refresh_started_at BIGINT NOT NULL DEFAULT 0;
