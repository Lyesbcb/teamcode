CREATE TABLE directives (
    member_id VARCHAR(36) PRIMARY KEY,
    id VARCHAR(36) NOT NULL,
    pace VARCHAR(20) NOT NULL,
    max_concurrent INT NOT NULL,
    min_interval BIGINT NOT NULL,
    message VARCHAR(1000) NOT NULL,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    FOREIGN KEY (member_id) REFERENCES members(id)
);
CREATE TABLE directive_receipts (
    directive_id VARCHAR(36) NOT NULL,
    token_id VARCHAR(36) NOT NULL,
    stage VARCHAR(32) NOT NULL,
    received_at BIGINT NOT NULL,
    PRIMARY KEY (directive_id, token_id, stage)
);
