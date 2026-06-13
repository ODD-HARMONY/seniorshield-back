package com.seniorshield.cache;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/** §1, §2: suspicion_reports + suspicion_aggregate DAO */
public class SuspicionDao {
    private static final Logger log = Logger.getLogger(SuspicionDao.class.getName());

    private static volatile HikariDataSource ds;

    private static HikariDataSource dataSource() {
        if (ds == null) {
            synchronized (SuspicionDao.class) {
                if (ds == null) {
                    HikariConfig cfg = new HikariConfig();
                    cfg.setDriverClassName("org.mariadb.jdbc.Driver");
                    cfg.setJdbcUrl(System.getenv("DB_URL"));
                    cfg.setUsername(System.getenv("MARIADB_USER"));
                    cfg.setPassword(System.getenv("MARIADB_PASSWORD"));
                    cfg.setMaximumPoolSize(5);
                    cfg.setConnectionTimeout(5_000);
                    ds = new HikariDataSource(cfg);
                }
            }
        }
        return ds;
    }

    public static class ReportResult {
        public boolean alreadyReported;
        public int     suspicionCount;
    }

    public static class AggregateEntry {
        public String url;
        public int    suspicionCount;
        public String lastReportedAt;
    }

    /** §2.1: "의심돼요" 신고 — 트랜잭션으로 두 테이블 동기화 */
    public ReportResult report(String canonicalUrl, String clientId) throws Exception {
        ReportResult result = new ReportResult();
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try {
                // 1. 원본 로그 INSERT
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO suspicion_reports (url, client_id) VALUES (?, ?)")) {
                    ps.setString(1, canonicalUrl);
                    ps.setString(2, clientId);
                    ps.executeUpdate();
                    result.alreadyReported = false;
                }
                // 2. 집계 업데이트
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO suspicion_aggregate (url, suspicion_count, last_reported_at) " +
                        "VALUES (?, 1, NOW()) " +
                        "ON DUPLICATE KEY UPDATE " +
                        "  suspicion_count = suspicion_count + 1, last_reported_at = NOW()")) {
                    ps.setString(1, canonicalUrl);
                    ps.executeUpdate();
                }
            } catch (SQLIntegrityConstraintViolationException e) {
                result.alreadyReported = true;
            }
            // 3. 현재 카운트 조회
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT suspicion_count FROM suspicion_aggregate WHERE url = ?")) {
                ps.setString(1, canonicalUrl);
                try (ResultSet rs = ps.executeQuery()) {
                    result.suspicionCount = rs.next() ? rs.getInt(1) : 0;
                }
            }
            c.commit();
        }
        return result;
    }

    /** §3: analyze 파이프라인에서 가중치 계산용 카운트 조회 */
    public int getCount(String canonicalUrl) {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT suspicion_count FROM suspicion_aggregate WHERE url = ?")) {
            ps.setString(1, canonicalUrl);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "SuspicionDao.getCount failed", e);
            return 0;
        }
    }

    /** §2.2: GET /api/suspicion 응답용 */
    public AggregateEntry getAggregate(String canonicalUrl) {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT url, suspicion_count, last_reported_at " +
                     "FROM suspicion_aggregate WHERE url = ?")) {
            ps.setString(1, canonicalUrl);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                AggregateEntry e = new AggregateEntry();
                e.url             = rs.getString("url");
                e.suspicionCount  = rs.getInt("suspicion_count");
                Timestamp ts      = rs.getTimestamp("last_reported_at");
                e.lastReportedAt  = ts != null ? ts.toInstant().toString() : null;
                return e;
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "SuspicionDao.getAggregate failed", e);
            return null;
        }
    }
}
