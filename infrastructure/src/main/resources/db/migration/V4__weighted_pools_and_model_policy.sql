ALTER TABLE member_policies MODIFY daily_units BIGINT NOT NULL DEFAULT 0;
ALTER TABLE member_policies ADD COLUMN weight INT NOT NULL DEFAULT 1;
CREATE TABLE pool_budgets (
    team_id VARCHAR(36) NOT NULL,
    pool_id VARCHAR(36) NOT NULL,
    daily_units BIGINT NOT NULL,
    PRIMARY KEY (team_id, pool_id),
    FOREIGN KEY (team_id) REFERENCES teams(id)
);
INSERT INTO pool_budgets (team_id, pool_id, daily_units) VALUES ('default', '', 1000000);
CREATE TABLE model_policies (
    team_id VARCHAR(36) PRIMARY KEY,
    allowed_models TEXT NOT NULL,
    denied_models TEXT NOT NULL,
    allowed_efforts TEXT NOT NULL,
    maximum_thinking_tokens BIGINT NOT NULL,
    FOREIGN KEY (team_id) REFERENCES teams(id)
);
ALTER TABLE reservations ADD COLUMN pool_id VARCHAR(36) NOT NULL DEFAULT '';
UPDATE reservations r JOIN accounts a ON a.id = r.account_id SET r.pool_id = COALESCE(a.pool_id, '');
CREATE INDEX pool_daily ON reservations(team_id, pool_id, day_number);
