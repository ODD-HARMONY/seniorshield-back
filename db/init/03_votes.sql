-- 투표 기능 테이블 (§suspicion 대체)
-- vote_type: 'ok'(괜찮아요) | 'suspicious'(이상해요)
-- 1인 1표 보장: UNIQUE KEY(url, client_id), 재투표 시 기존 투표 변경 허용

CREATE TABLE IF NOT EXISTS vote_records (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  url        VARCHAR(500) NOT NULL,
  client_id  CHAR(64) NOT NULL,
  vote_type  ENUM('ok', 'suspicious') NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_url_client (url, client_id),
  INDEX idx_url     (url),
  INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS vote_aggregate (
  url              VARCHAR(500) PRIMARY KEY,
  ok_count         INT DEFAULT 0,
  suspicious_count INT DEFAULT 0,
  last_voted_at    TIMESTAMP,
  INDEX idx_suspicious (suspicious_count DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
