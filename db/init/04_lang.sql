-- analysis_cache에 lang 컬럼 추가 + 복합 PK로 변경
-- 기존 데이터는 lang='ko'로 자동 채워짐
ALTER TABLE analysis_cache DROP PRIMARY KEY;
ALTER TABLE analysis_cache ADD COLUMN lang CHAR(2) NOT NULL DEFAULT 'ko' AFTER url_hash;
ALTER TABLE analysis_cache ADD PRIMARY KEY (url_hash, lang);
