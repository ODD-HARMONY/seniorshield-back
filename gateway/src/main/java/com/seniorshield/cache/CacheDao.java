package com.seniorshield.cache;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CacheDao {
    private static final Logger log = Logger.getLogger(CacheDao.class.getName());

    private static volatile HikariDataSource ds;

    // §10.3: 레이지 초기화 — 클래스 로드 시가 아닌 첫 쿼리 시 연결
    private static HikariDataSource dataSource() {
        if (ds == null) {
            synchronized (CacheDao.class) {
                if (ds == null) {
                    HikariConfig cfg = new HikariConfig();
                    cfg.setDriverClassName("org.mariadb.jdbc.Driver");
                    cfg.setJdbcUrl(System.getenv("DB_URL"));
                    cfg.setUsername(System.getenv("MARIADB_USER"));
                    cfg.setPassword(System.getenv("MARIADB_PASSWORD"));
                    cfg.setMaximumPoolSize(10);
                    cfg.setConnectionTimeout(5_000);
                    ds = new HikariDataSource(cfg);
                }
            }
        }
        return ds;
    }

    /** §10.2: 유효 캐시 조회. 모델 불일치·lang 불일치 시 미스 처리 */
    public CacheEntry findValid(String urlHash, String lang,
                                String modelClassify, String modelInfo, String modelImage) {
        String sql = "SELECT result, hit_count FROM analysis_cache " +
                     "WHERE url_hash = ? AND lang = ? AND expires_at > NOW() " +
                     "AND model_classify = ? AND model_info = ? AND model_image = ?";
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, urlHash);
            ps.setString(2, lang);
            ps.setString(3, modelClassify);
            ps.setString(4, modelInfo);
            ps.setString(5, modelImage);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String json = rs.getString("result");
                    int hits    = rs.getInt("hit_count");
                    incrementHit(urlHash, lang);
                    return new CacheEntry(json, hits);
                }
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "Cache lookup failed", e);
        }
        return null;
    }

    /** §10.2: 캐시 저장 */
    public void save(String urlHash, String lang, String resultJson,
                     String modelClassify, String modelInfo, String modelImage,
                     int ttlDays) {
        String sql = "INSERT INTO analysis_cache " +
                     "(url_hash, lang, result, model_classify, model_info, model_image, expires_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                     "ON DUPLICATE KEY UPDATE result=?, expires_at=?, " +
                     "model_classify=?, model_info=?, model_image=?";
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            LocalDateTime expires = LocalDateTime.now().plusDays(ttlDays);
            ps.setString(1, urlHash);
            ps.setString(2, lang);
            ps.setString(3, resultJson);
            ps.setString(4, modelClassify);
            ps.setString(5, modelInfo);
            ps.setString(6, modelImage);
            ps.setTimestamp(7, Timestamp.valueOf(expires));
            // ON DUPLICATE KEY
            ps.setString(8, resultJson);
            ps.setTimestamp(9, Timestamp.valueOf(expires));
            ps.setString(10, modelClassify);
            ps.setString(11, modelInfo);
            ps.setString(12, modelImage);
            ps.executeUpdate();
        } catch (Exception e) {
            log.log(Level.WARNING, "Cache save failed", e);
        }
    }

    public boolean ping() {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            st.execute("SELECT 1");
            return true;
        } catch (Exception e) {
            log.log(Level.WARNING, "DB ping failed: " + e.getMessage(), e);
            return false;
        }
    }

    private void incrementHit(String urlHash, String lang) {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE analysis_cache SET hit_count = hit_count + 1 " +
                     "WHERE url_hash = ? AND lang = ?")) {
            ps.setString(1, urlHash);
            ps.setString(2, lang);
            ps.executeUpdate();
        } catch (Exception e) {
            log.log(Level.FINE, "Hit count update failed", e);
        }
    }
}
