-- Keep the member table's original primary key and one-row contract during rolling upgrades.
ALTER TABLE directives
    ADD COLUMN scope_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '';
CREATE TABLE session_directives LIKE directives;
ALTER TABLE session_directives
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (member_id, scope_key),
    ADD FOREIGN KEY (member_id) REFERENCES members(id),
    ADD INDEX directive_expiry (expires_at);

ALTER TABLE reservations
    ADD COLUMN session_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD INDEX reservation_session (member_id, session_key, state, expires_at);

CREATE TABLE directive_deliveries (
    member_id VARCHAR(36) NOT NULL,
    session_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    stage VARCHAR(32) NOT NULL,
    directive_id VARCHAR(36) NOT NULL,
    claim_id VARCHAR(36) NOT NULL,
    token_id VARCHAR(36) NOT NULL,
    claimed_until BIGINT NOT NULL,
    emitted_at BIGINT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (member_id, session_key, stage),
    FOREIGN KEY (member_id) REFERENCES members(id),
    INDEX delivery_retention (updated_at)
);
