CREATE TABLE conversation_affinities (
    team_id VARCHAR(36) NOT NULL,
    member_id VARCHAR(36) NOT NULL,
    session_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    conversation_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requested_model VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    route_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider VARCHAR(32) NOT NULL,
    resolved_model VARCHAR(120) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    last_seen_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    PRIMARY KEY (team_id, member_id, conversation_key, requested_model, route_key),
    FOREIGN KEY (team_id) REFERENCES teams(id),
    FOREIGN KEY (member_id) REFERENCES members(id),
    FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE,
    INDEX affinity_expiry (expires_at),
    INDEX affinity_load (team_id, last_seen_at, account_id)
);
