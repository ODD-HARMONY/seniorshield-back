package com.seniorshield.pipeline;

import com.seniorshield.cache.CacheDao;
import com.seniorshield.cache.CacheEntry;
import com.seniorshield.cache.SuspicionDao;
import com.seniorshield.client.AnalyzerClient;
import com.seniorshield.client.ExtractorClient;
import com.seniorshield.client.FactCheckClient;
import com.seniorshield.model.*;
import com.seniorshield.util.HashUtil;
import com.seniorshield.util.JsonUtil;
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
    private final SuspicionDao     suspicionDao     = new SuspicionDao();
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

    public AnalyzeResponse analyze(String rawUrl) {
        long totalStart = System.currentTimeMillis();
        String jobId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        String normalizedUrl;
        String videoId;
        try {
            normalizedUrl = UrlValidator.normalize(rawUrl);
            videoId       = UrlValidator.extractVideoId(normalizedUrl);
        } catch (UrlValidator.InvalidUrlException e) {
            return errorResponse("invalid_url", e.getMessage(), jobId);
        }

        log.info("Analyze job=" + jobId + " video_id=" + videoId);

        // 캐시 조회
        String urlHash = HashUtil.sha256(normalizedUrl);
        CacheEntry cached = cacheDao.findValid(urlHash, modelClassify(), modelInfo(), modelImage());
        if (cached != null) {
            try {
                AnalyzeResponse r = JsonUtil.MAPPER.readValue(cached.resultJson, AnalyzeResponse.class);
                r.cached = true;
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
            extract = extractorClient.extract(normalizedUrl, frameCount());
            sExtract.ok        = true;
            sExtract.elapsedMs = System.currentTimeMillis() - t0;
        } catch (Exception e) {
            log.log(Level.WARNING, "Extract failed", e);
            return errorResponse("extractor_timeout", e.getMessage(), jobId);
        }

        String subtitleText = (extract.subtitle != null && extract.subtitle.available)
                ? extract.subtitle.text : "";
        List<String> frames = (extract.frames != null && extract.frames.framesBase64 != null)
                ? extract.frames.framesBase64 : List.of();

        // 2. 자막 분류
        ClassifyResult classify = null;
        t0 = System.currentTimeMillis();
        try {
            classify = analyzerClient.classify(subtitleText);
            sClassify.ok           = true;
            sClassify.elapsedMs    = System.currentTimeMillis() - t0;
            sClassify.informational = String.valueOf(classify.informational);
        } catch (Exception e) {
            log.log(Level.WARNING, "Classify failed", e);
            sClassify.ok    = false;
            sClassify.error = e.getMessage();
        }

        final ClassifyResult classifyF = classify;

        // 3. 이미지 + 정보 분석 병렬
        List<String> framesF = frames;
        CompletableFuture<ImageResult> imageF = CompletableFuture.supplyAsync(() -> {
            long ts = System.currentTimeMillis();
            try {
                ImageResult r = analyzerClient.image(framesF);
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
                        InfoResult r = analyzerClient.info(subtitleText, classifyF.category);
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

        // 4. 팩트체크 (info.claims 순차 호출)
        List<FactCheckResult> facts = new ArrayList<>();
        long tsfc = System.currentTimeMillis();
        if (info != null && info.claims != null) {
            for (InfoResult.Claim c : info.claims) {
                try {
                    facts.add(factCheckClient.check(c.normalized, "ko"));
                } catch (Exception e) {
                    log.log(Level.WARNING, "FactCheck failed for claim", e);
                }
            }
        }
        sFactCheck.ok        = true;
        sFactCheck.elapsedMs = System.currentTimeMillis() - tsfc;

        // 5. 통합 + 집단지성 가중치 (§3)
        int suspicionCount = suspicionDao.getCount(normalizedUrl);
        AnalyzeResponse.Verdict verdict = aggregator.aggregate(classify, info, image, facts);
        aggregator.applySuspicionWeight(verdict, suspicionCount);

        AnalyzeResponse response = new AnalyzeResponse();
        response.jobId          = jobId;
        response.videoId        = videoId;
        response.cached         = false;
        response.verdict        = verdict;
        response.stages         = stages;
        response.elapsedMsTotal = System.currentTimeMillis() - totalStart;

        // 6. 캐시 저장
        try {
            String json = JsonUtil.MAPPER.writeValueAsString(response);
            cacheDao.save(urlHash, json, modelClassify(), modelInfo(), modelImage(), cacheTtlDays());
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
