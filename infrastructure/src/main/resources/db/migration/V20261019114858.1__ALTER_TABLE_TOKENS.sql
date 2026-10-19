ALTER TABLE tokens ADD device_id VARCHAR(36) NULL;
ALTER TABLE tokens ADD created_at BIGINT NULL;
CREATE INDEX token_device ON tokens (device_id);
ALTER TABLE tokens ADD CONSTRAINT fk_tokens_device_id__id FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE CASCADE ON UPDATE RESTRICT;