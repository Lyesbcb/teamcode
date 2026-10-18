CREATE TABLE model_routes (
    team_id VARCHAR(36) NOT NULL,
    id VARCHAR(36) NOT NULL,
    model_pattern VARCHAR(120) NOT NULL,
    targets TEXT NOT NULL,
    priority INT NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL,
    PRIMARY KEY (team_id, id),
    FOREIGN KEY (team_id) REFERENCES teams(id)
);
