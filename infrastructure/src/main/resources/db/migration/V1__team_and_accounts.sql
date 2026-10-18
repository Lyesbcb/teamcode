CREATE TABLE teams (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(120) NOT NULL
);
INSERT INTO teams (id, name) VALUES ('default', 'TeamCode');
CREATE TABLE members (
    id VARCHAR(36) PRIMARY KEY,
    team_id VARCHAR(36) NOT NULL,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(16) NOT NULL,
    disabled BOOLEAN NOT NULL DEFAULT FALSE,
    must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
    failed_logins INT NOT NULL DEFAULT 0,
    locked_until BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY member_username (team_id, username),
    FOREIGN KEY (team_id) REFERENCES teams(id)
);
CREATE TABLE tokens (
    id VARCHAR(36) PRIMARY KEY,
    member_id VARCHAR(36) NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    device VARCHAR(120) NOT NULL,
    expires_at BIGINT NOT NULL,
    FOREIGN KEY (member_id) REFERENCES members(id),
    INDEX token_member (member_id)
);
CREATE TABLE accounts (
    id VARCHAR(36) PRIMARY KEY,
    team_id VARCHAR(36) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    provider_account_id VARCHAR(128) NOT NULL,
    organization_id VARCHAR(128) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    credential_version BIGINT NOT NULL,
    credential_envelope MEDIUMTEXT NOT NULL,
    disabled BOOLEAN NOT NULL DEFAULT FALSE,
    pool_id VARCHAR(36),
    priority INT NOT NULL DEFAULT 0,
    state VARCHAR(32) NOT NULL,
    UNIQUE KEY provider_identity (team_id, provider, provider_account_id, organization_id),
    FOREIGN KEY (team_id) REFERENCES teams(id)
);
CREATE TABLE enrollment_operations (
    team_id VARCHAR(36) NOT NULL,
    operation_id VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    created BOOLEAN NOT NULL,
    PRIMARY KEY (team_id, operation_id),
    FOREIGN KEY (account_id) REFERENCES accounts(id)
);
CREATE TABLE audit_events (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    team_id VARCHAR(36) NOT NULL,
    actor_id VARCHAR(36) NOT NULL,
    action VARCHAR(64) NOT NULL,
    subject_id VARCHAR(36) NOT NULL,
    occurred_at BIGINT NOT NULL,
    INDEX audit_team_time (team_id, occurred_at)
);
