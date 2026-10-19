-- An observed percentage is useful even when the provider omits the reset epoch.
ALTER TABLE quota_windows MODIFY COLUMN resets_at BIGINT NULL;
ALTER TABLE quota_history MODIFY COLUMN resets_at BIGINT NULL;
