CREATE TABLE IF NOT EXISTS analysis_cache (
  url_hash       CHAR(64)     PRIMARY KEY,
  result         JSON         NOT NULL,
  model_classify VARCHAR(64)  NOT NULL,
  model_info     VARCHAR(64)  NOT NULL,
  model_image    VARCHAR(64)  NOT NULL,
  created_at     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
  expires_at     TIMESTAMP    NOT NULL,
  hit_count      INT          DEFAULT 0,
  INDEX idx_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
