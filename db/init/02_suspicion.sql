-- §1: 집단지성 테이블
CREATE TABLE IF NOT EXISTS suspicion_reports (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  url VARCHAR(500) NOT NULL,
  client_id CHAR(64) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_url_client (url, client_id),
  INDEX idx_url (url),
  INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS suspicion_aggregate (
  url VARCHAR(500) PRIMARY KEY,
  suspicion_count INT DEFAULT 0,
  last_reported_at TIMESTAMP,
  INDEX idx_count (suspicion_count DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
