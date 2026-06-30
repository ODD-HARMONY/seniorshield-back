package com.seniorshield.cache;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/** 투표(괜찮아요/이상해요) DAO — vote_records + vote_aggregate */
public class VoteDao {
    private static final Logger log = Logger.getLogger(VoteDao.class.getName());

    private static volatile HikariDataSource ds;

    private static HikariDataSource dataSource() {
        if (ds == null) {
            synchronized (VoteDao.class) {
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

    public static class VoteResult {
        public String  voteType;      // "ok" | "suspicious"
        public boolean alreadyVoted;  // 같은 타입으로 이미 투표함
        public boolean changed;       // 이전 투표에서 타입을 변경함
        public int     okCount;
        public int     suspiciousCount;
    }

    public static class AggregateEntry {
        public String url;
        public int    okCount;
        public int    suspiciousCount;
        public String lastVotedAt;
    }

    /** 투표 제출 — 신규/재투표(타입 변경) 모두 처리 */
    public VoteResult vote(String canonicalUrl, String clientId, String voteType) throws Exception {
        VoteResult result = new VoteResult();
        result.voteType = voteType;

        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try {
                // 기존 투표 확인
                String existing = null;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT vote_type FROM vote_records WHERE url = ? AND client_id = ? FOR UPDATE")) {
                    ps.setString(1, canonicalUrl);
                    ps.setString(2, clientId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) existing = rs.getString(1);
                    }
                }

                if (existing == null) {
                    // 신규 투표
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO vote_records (url, client_id, vote_type) VALUES (?, ?, ?)")) {
                        ps.setString(1, canonicalUrl);
                        ps.setString(2, clientId);
                        ps.setString(3, voteType);
                        ps.executeUpdate();
                    }
                    adjustAggregate(c, canonicalUrl, voteType, 1);
                    result.alreadyVoted = false;
                    result.changed      = false;

                } else if (existing.equals(voteType)) {
                    // 동일 타입 재투표 → 변경 없음
                    result.alreadyVoted = true;
                    result.changed      = false;

                } else {
                    // 다른 타입으로 변경
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE vote_records SET vote_type = ? WHERE url = ? AND client_id = ?")) {
                        ps.setString(1, voteType);
                        ps.setString(2, canonicalUrl);
                        ps.setString(3, clientId);
                        ps.executeUpdate();
                    }
                    adjustAggregate(c, canonicalUrl, existing,  -1); // 기존 타입 감소
                    adjustAggregate(c, canonicalUrl, voteType,   1); // 새 타입 증가
                    result.alreadyVoted = false;
                    result.changed      = true;
                }

                // 최신 집계 조회
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT ok_count, suspicious_count FROM vote_aggregate WHERE url = ?")) {
                    ps.setString(1, canonicalUrl);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            result.okCount         = rs.getInt("ok_count");
                            result.suspiciousCount = rs.getInt("suspicious_count");
                        }
                    }
                }

                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
        return result;
    }

    /** analyze 파이프라인에서 가중치 계산용 조회 */
    public int[] getCounts(String canonicalUrl) {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ok_count, suspicious_count FROM vote_aggregate WHERE url = ?")) {
            ps.setString(1, canonicalUrl);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new int[]{ rs.getInt(1), rs.getInt(2) };
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "VoteDao.getCounts failed", e);
        }
        return new int[]{ 0, 0 };
    }

    /** GET /api/vote 응답용 */
    public AggregateEntry getAggregate(String canonicalUrl) {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT url, ok_count, suspicious_count, last_voted_at " +
                     "FROM vote_aggregate WHERE url = ?")) {
            ps.setString(1, canonicalUrl);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                AggregateEntry e  = new AggregateEntry();
                e.url             = rs.getString("url");
                e.okCount         = rs.getInt("ok_count");
                e.suspiciousCount = rs.getInt("suspicious_count");
                Timestamp ts      = rs.getTimestamp("last_voted_at");
                e.lastVotedAt     = ts != null ? ts.toInstant().toString() : null;
                return e;
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "VoteDao.getAggregate failed", e);
            return null;
        }
    }

    /** ok_count 또는 suspicious_count를 delta(+1/-1)만큼 조정 */
    private void adjustAggregate(Connection c, String url, String voteType, int delta) throws Exception {
        boolean isOk = "ok".equals(voteType);
        String sql = "INSERT INTO vote_aggregate (url, ok_count, suspicious_count, last_voted_at) " +
                     "VALUES (?, ?, ?, NOW()) " +
                     "ON DUPLICATE KEY UPDATE " +
                     (isOk ? "ok_count = GREATEST(0, ok_count + ?)"
                            : "suspicious_count = GREATEST(0, suspicious_count + ?)") +
                     ", last_voted_at = NOW()";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, url);
            ps.setInt(2, isOk ? delta : 0);
            ps.setInt(3, isOk ? 0 : delta);
            ps.setInt(4, delta);
            ps.executeUpdate();
        }
    }
}
