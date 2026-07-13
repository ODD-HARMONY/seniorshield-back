package com.seniorshield.pipeline;

import com.seniorshield.cache.CacheDao;
import com.seniorshield.cache.CacheEntry;
import com.seniorshield.cache.VoteDao;
import com.seniorshield.client.AnalyzerClient;
import com.seniorshield.client.ExtractorClient;
import com.seniorshield.client.FactCheckClient;
import com.seniorshield.model.*;
import com.seniorshield.model.AdResult;
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
        AnalyzeResponse.StageStatus sAdVerify  = new AnalyzeResponse.StageStatus();
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
                classify = analyzerClient.classify(subtitleText, extract.title, extract.description, lang);

                sClassify.ok            = true;
                sClassify.elapsedMs     = System.currentTimeMillis() - t0;
                sClassify.informational = String.valueOf(classify.informational);
            } catch (Exception e) {
                log.log(Level.WARNING, "Classify failed", e);
                sClassify.ok    = false;
                sClassify.error = e.getMessage();
            }
        }

        if (classify != null) {
            int claimCount = classify.checkableClaims != null ? classify.checkableClaims.size() : 0;
            if (classify.informational) {
                log.info("job=" + jobId + " classify: informational=true category=" + classify.category
                        + " checkable_claims=" + claimCount
                        + " key_topic=" + classify.keyTopic
                        + " advertisement=" + classify.advertisement + " ad_label=" + classify.adLabel);
                log.info("job=" + jobId + " key_claim=" + trunc(classify.keyClaim, 150));
            } else {
                log.info("job=" + jobId + " classify: informational=false category=" + classify.category
                        + " checkable_claims=" + claimCount
                        + " advertisement=" + classify.advertisement + " ad_label=" + classify.adLabel);
            }
        }

        final ClassifyResult classifyF = classify;
        final String langF = lang;
        final ExtractResult extractF = extract;
        final String subtitleTextF = subtitleText;

        // ad_verify 필요 여부: classify가 likely_false_ad 또는 likely_scam으로 판정하고 자막+프레임이 있을 때
        boolean needsAdVerify = classifyF != null && classifyF.advertisement
                && ("likely_false_ad".equals(classifyF.adLabel) || "likely_scam".equals(classifyF.adLabel))
                && !frames.isEmpty() && hasSubtitle;
        if (needsAdVerify) {
            stages.put("ad_verify", sAdVerify);
        }

        // 3. 이미지 + 정보 + 광고검증 병렬
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

        boolean ranInfo = classifyF != null
                && (classifyF.informational || classifyF.hasCheckableClaims());
        if (ranInfo && !classifyF.informational) {
            int n = classifyF.checkableClaims != null ? classifyF.checkableClaims.size() : 0;
            log.info("job=" + jobId + " soft-gate triggered: informational=false but checkable_claims=" + n);
        }
        CompletableFuture<InfoResult> infoF = ranInfo
                ? CompletableFuture.supplyAsync(() -> {
                    long ts = System.currentTimeMillis();
                    try {
                        String asl = classifyF.aiScriptLikelihood != null ? classifyF.aiScriptLikelihood : "low";
                        InfoResult r = analyzerClient.info(classifyF.keyClaim, classifyF.category,
                                subtitleTextF, extractF.title, extractF.description, langF, asl);
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

        CompletableFuture<AdResult> adF = needsAdVerify
                ? CompletableFuture.supplyAsync(() -> {
                    long ts = System.currentTimeMillis();
                    try {
                        AdResult r = analyzerClient.ad(framesF, subtitleTextF, classifyF.adLabel, langF);
                        sAdVerify.ok        = true;
                        sAdVerify.elapsedMs = System.currentTimeMillis() - ts;
                        return r;
                    } catch (Exception e) {
                        sAdVerify.ok    = false;
                        sAdVerify.error = e.getMessage();
                        log.log(Level.WARNING, "AdVerify failed", e);
                        return null;
                    }
                })
                : CompletableFuture.completedFuture(null);

        ImageResult image    = imageF.join();
        InfoResult  info     = infoF.join();
        AdResult    adResult = adF.join();

        if (info != null) {
            log.info("job=" + jobId + " info: overall=" + info.overallJudgement
                    + " confidence=" + info.confidence
                    + " claims=" + (info.claims != null ? info.claims.size() : 0));
            if (info.claims != null) {
                for (int ci = 0; ci < info.claims.size(); ci++) {
                    InfoResult.Claim c = info.claims.get(ci);
                    log.info("job=" + jobId + " claim[" + ci + "] judgement=" + c.preliminaryJudgement
                            + " text=" + trunc(c.text, 120));
                }
            }
        }

        // revision_notes, _internal 로그 기록 후 프론트 응답에서 제거
        if (image != null) {
            log.info("job=" + jobId + " image_label=" + image.label
                    + " confidence=" + image.confidence
                    + " revision_notes=" + image.revisionNotes);
            if (image.perFrame != null && !image.perFrame.isEmpty()) {
                StringBuilder pf = new StringBuilder("job=" + jobId + " per_frame=[");
                for (int i = 0; i < image.perFrame.size(); i++) {
                    ImageResult.FrameResult fr = image.perFrame.get(i);
                    if (i > 0) pf.append(", ");
                    pf.append(fr.index).append(":").append(fr.label).append(":").append(fr.confidence);
                }
                pf.append("]");
                log.info(pf.toString());
            }
            boolean debugMode = "true".equalsIgnoreCase(System.getenv("DEBUG_INCLUDE_INTERNAL"));
            if (!debugMode) {
                image.revisionNotes = null;
                image.internal      = null;
            }
        }
        if (adResult != null) {
            log.info("job=" + jobId + " ad_label=" + adResult.label
                    + " ad_confidence=" + adResult.confidence
                    + " reason=" + adResult.reason);
        }
        if (!sInfo.ok && !ranInfo) {
            sInfo.ok = true; // info skip (soft-gate 미충족) — ok 처리
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

        for (int fi = 0; fi < facts.size(); fi++) {
            FactCheckResult fc = facts.get(fi);
            int matchCount = (fc.matches != null) ? fc.matches.size() : 0;
            String topRating = (fc.matches != null && !fc.matches.isEmpty())
                    ? fc.matches.get(0).ratingNormalized : "none";
            log.info("job=" + jobId + " factcheck[" + fi + "] matches=" + matchCount
                    + " top_rating=" + topRating
                    + " lang_used=" + fc.languageUsed
                    + " fallback=" + fc.fallbackApplied);
        }

        // 5. 통합 + 집단지성 가중치
        int[] voteCounts = voteDao.getCounts(normalizedUrl); // [okCount, suspiciousCount]
        AnalyzeResponse.Verdict verdict = aggregator.aggregate(classify, info, image, facts, adResult, lang);
        String category = (classify != null && classify.category != null) ? classify.category : "";
        aggregator.applySuspicionWeight(verdict, voteCounts[0], voteCounts[1], category, lang);

        // 핵심 단계 실패 시 display_message 재설정 (uncertain 결과를 정상으로 오해하지 않도록)
        boolean classifyFailed = !sClassify.ok;
        boolean imageFailed    = !sImage.ok;
        if (classifyFailed || imageFailed) {
            verdict.displayMessage = "en".equals(lang)
                    ? "Analysis could not be completed. Please try again later."
                    : "분석을 완료하지 못했어요. 잠시 후 다시 시도해주세요";
            log.warning("Partial analysis job=" + jobId
                    + " classifyOk=" + sClassify.ok + " imageOk=" + sImage.ok);
        }

        AnalyzeResponse response = new AnalyzeResponse();
        response.jobId          = jobId;
        response.videoId        = videoId;
        response.lang           = lang;
        response.cached         = false;
        response.verdict        = verdict;
        response.stages         = stages;
        response.elapsedMsTotal = System.currentTimeMillis() - totalStart;

        AnalyzeResponse.Misinformation mis = response.verdict != null ? response.verdict.misinformation : null;
        AnalyzeResponse.AiGenerated    ag  = response.verdict != null ? response.verdict.aiGenerated    : null;
        log.info("job=" + jobId + " verdict:"
                + " ai=" + (ag  != null ? ag.label  + "(" + ag.confidence  + ")" : "n/a")
                + " misinfo=" + (mis != null && mis.applicable
                        ? mis.label + "(" + mis.confidence + ")" : "n/a")
                + " ad=" + (response.verdict != null && response.verdict.advertisement != null
                        && response.verdict.advertisement.applicable
                        ? response.verdict.advertisement.label : "n/a")
                + " elapsed=" + response.elapsedMsTotal + "ms");

        // 6. 캐시 저장 — 핵심 단계 실패 시 캐시 저장 건너뜀 (재시도 시 재분석 가능하도록)
        if (!classifyFailed && !imageFailed) {
            try {
                String json = JsonUtil.MAPPER.writeValueAsString(response);
                cacheDao.save(urlHash, lang, json, modelClassify(), modelInfo(), modelImage(), cacheTtlDays());
            } catch (Exception e) {
                log.warning("Cache save failed: " + e.getMessage());
            }
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

    private static String trunc(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
