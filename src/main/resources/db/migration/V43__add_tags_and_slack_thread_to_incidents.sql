ALTER TABLE incidents ADD COLUMN tags             TEXT;         -- JSON object of string key/value tags
ALTER TABLE incidents ADD COLUMN slack_channel_id  VARCHAR(20);
ALTER TABLE incidents ADD COLUMN slack_thread_ts   VARCHAR(30);
