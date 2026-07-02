package com.seniorshield.pipeline;

import com.seniorshield.cache.CacheDao;
import com.seniorshield.cache.CacheEntry;
import com.seniorshield.cache.VoteDao;
import com.seniorshield.client.AnalyzerClient;
import com.seniorshield.client.ExtractorClient;
import com.seniorshield.client.FactCheckClient;
import com.seniorshield.model.*;
import com.seniorshield.util.HashUtil;
import com.seniorshield.util.JsonUtil;
import com.seniorshield.util.LangValidator;
import com.seniorshield.util.UrlValidator;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/** §11.4 오케스트레이션 */
public class AnalysisPipeline {
    private static final Logger log = Logger.getLogger(AnalysisPipeline.class.getName());

    private static final AnalysisPipeline INSTANCE = new AnalysisPipeline();
    public static AnalysisPipeline getInstance() { return INSTANCE; }

    private final ExtractorClient  extractorClient  = new ExtractorClient();
    private final AnalyzerClient   analyzerClient   = new AnalyzerClient();
    private final FactCheckClient  factCheckClient  = new FactCheckClient();
    private final CacheDao         cacheDao         = new CacheDao();
    private final VoteDao          voteDao          = new VoteDao();
    private final ResultAggregator aggregator       = new ResultAggregator();

    private AnalysisPipeline() {}

    private String modelClassify() { return System.getenv().getOrDefault("MODEL_CLASSIFY", ""); }
    private String modelInfo()     { return System.getenv().getOrDefault("MODEL_INFO",     ""); }
    private String modelImage()    { return System.getenv().getOrDefault("MODEL_IMAGE",    ""); }
    private int    cacheTtlDays()  {
        String v = System.getenv("CACHE_TTL_DAYS");
        return v != null ? Integer.parseInt(v) : 14;
    }
    private int    frameCount()    {
        String v = System.getenv("FRAME_COUNT");
        return v != null ? Integer.parseInt(v) : 3;
    }

    public AnalyzeResponse analyze(String rawUrl, String rawLang) {
        long totalStart = System.currentTimeMillis();
        String jobId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String lang  = LangValidator.normalize(rawLang);

        String normalizedUrl;
        String videoId;
        try {
            normalizedUrl = UrlValidator.normalize(rawUrl);
            videoId       = UrlValidator.extractVideoId(normalizedUrl);
        } catch (UrlValidator.InvalidUrlException e) {
            return errorResponse("invalid_url", e.getMessage(), jobId);
        }

        log.info("Analyze job=" + jobId + " video_id=" + videoId + " lang=" + lang);

        // 캐시 조회 — (url_hash, lang) 복합 키
        String urlHash = HashUtil.sha256(normalizedUrl);
        CacheEntry cached = cacheDao.findValid(urlHash, lang, modelClassify(), modelInfo(), modelImage());
        if (cached != null) {
            try {
                AnalyzeResponse r = JsonUtil.MAPPER.readValue(cached.resultJson, AnalyzeResponse.class);
                r.cached = true;
                r.lang   = lang;
                return r;
            } catch (Exception e) {
                log.warning("Cache deserialization failed: " + e.getMessage());
            }
        }

        Map<String, AnalyzeResponse.StageStatus> stages = new LinkedHashMap<>();
        AnalyzeResponse.StageStatus sExtract   = new AnalyzeResponse.StageStatus();
        AnalyzeResponse.StageStatus sClassify  = new AnalyzeResponse.StageStatus();
        AnalyzeResponse.StageStatus sInfo      = new AnalyzeResponse.StageStatus();
        AnalyzeResponse.StageStatus sImage     = new AnalyzeResponse.StageStatus();
        AnalyzeResponse.StageStatus sFactCheck = new AnalyzeResponse.StageStatus();
        stages.put("extract",   sExtract);
        stages.put("classify",  sClassify);
        stages.put("info",      sInfo);
        stages.put("image",     sImage);
        stages.put("factcheck", sFactCheck);

        // 1. 추출
        ExtractResult extract;
        long t0 = System.currentTimeMillis();
        try {
            extract = extractorClient.extract(normalizedUrl, frameCount(), lang);
            sExtract.ok        = true;
            sExtract.elapsedMs = System.currentTimeMillis() - t0;
        } catch (Exception e) {
            log.log(Level.WARNING, "Extract failed", e);
            String msg = e.getMessage() != null ? e.getMessage() : "";
            String errCode = msg.contains("video_too_long")    ? "video_too_long"
                           : msg.contains("Video unavailable") ? "video_unavailable"
                           : msg.contains("invalid_url")       ? "invalid_url"
                           : "extractor_error";
            return errorResponse(errCode, msg, jobId);
        }

        if (extract.subtitle != null) {
            log.info("Subtitle survey job=" + jobId
                    + " reason=" + extract.subtitle.selectionReason
                    + " lang=" + extract.subtitle.language
                    + " original=" + extract.subtitle.originalLanguage
                    + " manual=" + (extract.subtitle.availableLangs != null ? extract.subtitle.availableLangs.manual : "[]")
                    + " auto_count=" + (extract.subtitle.availableLangs != null && extract.subtitle.availableLangs.auto != null
                            ? extract.subtitle.availableLangs.auto.size() : 0));
        }

        boolean hasSubtitle = extract.subtitle != null && extract.subtitle.available
                && extract.subtitle.text != null && !extract.subtitle.text.isEmpty();
        String subtitleText = hasSubtitle ? extract.subtitle.text : "";
        List<String> frames = (extract.frames != null && extract.frames.framesBase64 != null)
                ? extract.frames.framesBase64 : List.of();

        // 2. 자막 분류 — 자막 없으면 API 호출 없이 스킵
        ClassifyResult classify = null;
        t0 = System.currentTimeMillis();
        if (!hasSubtitle) {
            classify                = ClassifyResult.notApplicable();
            sClassify.ok            = true;
            sClassify.elapsedMs     = 0;
            sClassify.informational = "false";
            log.info("No subtitle for job=" + jobId + ", skipping classify");
        } else {
            try {
                classify = analyzerClient.classify(subtitleText, lang);
                sClassify.ok            = true;
                sClassify.elapsedMs     = System.currentTimeMillis() - t0;
                sClassify.informational = String.valueOf(classify.informational);
            } catch (Exception e) {
                log.log(Level.WARNING, "Classify failed", e);
                sClassify.ok    = false;
                sClassify.error = e.getMessage();
            }
        }

        final ClassifyResult classifyF = classify;
        final String langF = lang;

        // 3. 이미지 + 정보 분석 병렬
        List<String> framesF = frames;
        CompletableFuture<ImageResult> imageF = CompletableFuture.supplyAsync(() -> {
            long ts = System.currentTimeMillis();
            try {
                ImageResult r = analyzerClient.image(framesF, langF);
                sImage.ok        = true;
                sImage.elapsedMs = System.currentTimeMillis() - ts;
                return r;
            } catch (Exception e) {
                sImage.ok    = false;
                sImage.error = e.getMessage();
                return null;
            }
        });

        CompletableFuture<InfoResult> infoF = (classifyF != null && classifyF.informational)
                ? CompletableFuture.supplyAsync(() -> {
                    long ts = System.currentTimeMillis();
                    try {
                        InfoResult r = analyzerClient.info(subtitleText, classifyF.category, langF);
                        sInfo.ok        = true;
                        sInfo.elapsedMs = System.currentTimeMillis() - ts;
                        return r;
                    } catch (Exception e) {
                        sInfo.ok    = false;
                        sInfo.error = e.getMessage();
                        return null;
                    }
                })
                : CompletableFuture.completedFuture(null);

        ImageResult image = imageF.join();
        InfoResult  info  = infoF.join();
        if (!sInfo.ok && classifyF != null && !classifyF.informational) {
            sInfo.ok = true; // 정보성 아닌 경우 info는 skip — ok 처리
        }

        // 4. 팩트체크 (info.claims 순차 호출, lang 기준 fallback 포함)
        List<FactCheckResult> facts = new ArrayList<>();
        long tsfc = System.currentTimeMillis();
        if (info != null && info.claims != null) {
            for (InfoResult.Claim c : info.claims) {
                try {
                    facts.add(factCheckClient.check(c.normalized, lang));
                } catch (Exception e) {
                    log.log(Level.WARNING, "FactCheck failed for claim", e);
                }
            }
        }
        sFactCheck.ok        = true;
        sFactCheck.elapsedMs = System.currentTimeMillis() - tsfc;

        // 5. 통합 + 집단지성 가중치
        int[] voteCounts = voteDao.getCounts(normalizedUrl); // [okCount, suspiciousCount]
        AnalyzeResponse.Verdict verdict = aggregator.aggregate(classify, info, image, facts, lang);
        aggregator.applySuspicionWeight(verdict, voteCounts[0], voteCounts[1], lang);

        AnalyzeResponse response = new AnalyzeResponse();
        response.jobId          = jobId;
        response.videoId        = videoId;
        response.lang           = lang;
        response.cached         = false;
        response.verdict        = verdict;
        response.stages         = stages;
        response.elapsedMsTotal = System.currentTimeMillis() - totalStart;

        // 6. 캐시 저장 — (url_hash, lang) 복합 키
        try {
            String json = JsonUtil.MAPPER.writeValueAsString(response);
            cacheDao.save(urlHash, lang, json, modelClassify(), modelInfo(), modelImage(), cacheTtlDays());
        } catch (Exception e) {
            log.warning("Cache save failed: " + e.getMessage());
        }

        return response;
    }

    private AnalyzeResponse errorResponse(String error, String detail, String jobId) {
        AnalyzeResponse r = new AnalyzeResponse();
        r.jobId  = jobId;
        r.error  = error;
        r.detail = detail;
        return r;
    }
}
