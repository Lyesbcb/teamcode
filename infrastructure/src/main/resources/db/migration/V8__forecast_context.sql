-- Old hints have no trustworthy original protocol until the next admission updates them.
ALTER TABLE conversation_affinities ADD COLUMN wire_protocol VARCHAR(20) NULL;
ALTER TABLE conversation_affinities ADD COLUMN requested_effort VARCHAR(32) NULL;
ALTER TABLE conversation_affinities ADD COLUMN thinking_tokens BIGINT NULL;
ALTER TABLE directives ADD COLUMN context_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;
ALTER TABLE directives ADD COLUMN recovery_pace VARCHAR(20) NULL;
ALTER TABLE directives ADD COLUMN recovery_started_at BIGINT NULL;
