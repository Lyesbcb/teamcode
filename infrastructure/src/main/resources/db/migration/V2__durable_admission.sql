ALTER TABLE accounts ADD COLUMN cooldown_until BIGINT NOT NULL DEFAULT 0;
CREATE TABLE member_policies (
    member_id VARCHAR(36) PRIMARY KEY,
    daily_units BIGINT NOT NULL DEFAULT 1000000,
    max_concurrent INT NOT NULL DEFAULT 2,
    pool_id VARCHAR(36),
    FOREIGN KEY (member_id) REFERENCES members(id)
);
CREATE TABLE reservations (
    id VARCHAR(36) PRIMARY KEY,
    team_id VARCHAR(36) NOT NULL,
    member_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    day_number BIGINT NOT NULL,
    units BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    expires_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    FOREIGN KEY (member_id) REFERENCES members(id),
    FOREIGN KEY (account_id) REFERENCES accounts(id),
    INDEX member_daily (member_id, day_number),
    INDEX active_account (account_id, state, expires_at)
);
CREATE TABLE quota_windows (
    account_id VARCHAR(36) NOT NULL,
    window_name VARCHAR(80) NOT NULL,
    utilization DOUBLE NOT NULL,
    observed_at BIGINT NOT NULL,
    resets_at BIGINT NOT NULL,
    PRIMARY KEY (account_id, window_name),
    FOREIGN KEY (account_id) REFERENCES accounts(id)
);
CREATE TABLE quota_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_id VARCHAR(36) NOT NULL,
    window_name VARCHAR(80) NOT NULL,
    utilization DOUBLE NOT NULL,
    observed_at BIGINT NOT NULL,
    resets_at BIGINT NOT NULL,
    sample_minute BIGINT NOT NULL,
    UNIQUE KEY quota_sample (account_id, window_name, sample_minute),
    INDEX quota_recent (account_id, window_name, observed_at)
);
