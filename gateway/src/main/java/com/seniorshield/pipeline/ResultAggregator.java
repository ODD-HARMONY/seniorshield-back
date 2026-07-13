package com.seniorshield.pipeline;

import com.seniorshield.model.*;
import com.seniorshield.model.AdResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** §9: 최종 verdict 생성 규칙 */
public class ResultAggregator {

    // ── R2 설정 (환경변수, 기본값 폴백) ──────────────────────────────────
    private static final boolean R2_ENABLED = !"false".equalsIgnoreCase(
            System.getenv("R2_UNCERTAIN_ESCALATION_ENABLED"));
    private static final double  R2_IMAGE_CONF_THRESHOLD = parseEnvDouble(
            System.getenv("R2_IMAGE_CONF_THRESHOLD"), 0.50);
    private static final Set<String> R2_RISK_CATEGORIES = parseEnvSet(
            System.getenv("R2_RISK_CATEGORIES"), "health,finance,science");
    private static final boolean R2_ALLOW_FINANCE_CHAIN = !"false".equalsIgnoreCase(
            System.getenv("R2_ALLOW_FINANCE_CHAIN"));

    private static double parseEnvDouble(String val, double def) {
        try { return val != null ? Double.parseDouble(val.trim()) : def; }
        catch (NumberFormatException e) { return def; }
    }
    private static Set<String> parseEnvSet(String val, String def) {
        String csv = (val != null && !val.isBlank()) ? val : def;
        return new HashSet<>(Arrays.asList(csv.split(",")));
    }

    public AnalyzeResponse.Verdict aggregate(
            ClassifyResult classify,
            InfoResult info,
            ImageResult image,
            List<FactCheckResult> facts,
            AdResult ad,
            String lang) {

        AnalyzeResponse.Verdict v = new AnalyzeResponse.Verdict();

        // §9.1 AI 생성 판별
        v.aiGenerated = buildAiGenerated(image);

        String category = (classify != null && classify.category != null) ? classify.category : "";

        // §9.2 허위정보 판별
        v.misinformation = buildMisinformation(classify, info, facts, category, image);

        // §9.3 광고·사기 판별
        v.advertisement = buildAdvertisement(classify, ad);

        v.category = category.isEmpty() ? null : category;

        // §9.4 display_message (communitySignal은 applySuspicionWeight에서 채워지므로 null 전달)
        v.displayMessage = buildMessage(v.aiGenerated, v.misinformation, v.advertisement, null, category, lang);

        return v;
    }

    private AnalyzeResponse.Advertisement buildAdvertisement(ClassifyResult classify, AdResult ad) {
        AnalyzeResponse.Advertisement adv = new AnalyzeResponse.Advertisement();
        if (classify == null || !classify.advertisement) {
            adv.applicable = false;
            return adv;
        }
        adv.applicable = true;
        if (ad != null) {
            // ad_verify 결과 사용
            adv.label      = ad.label;
            adv.confidence = ad.confidence;
            adv.reason     = ad.reason;
        } else {
            // ad_verify 미실행 (normal_ad) 또는 실패 시 classify 결과 그대로
            adv.label      = classify.adLabel != null ? classify.adLabel : "normal_ad";
            adv.confidence = null;
        }
        return adv;
    }

    private AnalyzeResponse.AiGenerated buildAiGenerated(ImageResult image) {
        AnalyzeResponse.AiGenerated ag = new AnalyzeResponse.AiGenerated();
        if (image != null) {
            ag.label      = image.label;
            ag.confidence = image.confidence;
            ag.evidence   = null;
        } else {
            ag.label      = "uncertain";
            ag.confidence = 0.0;
            ag.evidence   = null;
        }
        return ag;
    }

    private AnalyzeResponse.Misinformation buildMisinformation(
            ClassifyResult classify, InfoResult info, List<FactCheckResult> facts,
            String category, ImageResult image) {

        AnalyzeResponse.Misinformation m = new AnalyzeResponse.Misinformation();

        if (classify == null || !classify.informational) {
            m.applicable = false;
            return m;
        }
        m.applicable = true;

        if (info == null) {
            m.label      = "uncertain";
            m.confidence = 0.0;
            return m;
        }

        // 팩트체크 매칭 있는 경우
        String fcRating = dominantFactCheckRating(facts);
        if (fcRating != null) {
            if (fcRating.equals("false") || fcRating.equals("mostly_false")) {
                m.label      = "false";
                m.confidence = Math.max(info.confidence, 0.9);
            } else if (fcRating.equals("true") || fcRating.equals("mostly_true")) {
                m.label      = "true";
                m.confidence = info.confidence;
            } else {
                m.label      = info.overallJudgement;
                m.confidence = info.confidence;
            }
        } else {
            m.label      = info.overallJudgement;
            m.confidence = info.confidence;
        }

        // ① finance 하향: true/likely_true → uncertain (투자 정보는 확정 불가)
        String labelBeforeFinance = m.label;
        if ("finance".equals(category)
                && ("true".equals(m.label) || "likely_true".equals(m.label))) {
            m.label = "uncertain";
        }

        // ② R2: AI 영상 + 위험 카테고리 + uncertain → likely_false 상향
        //    순서 보장: factcheck override(①보다 앞) → finance 하향(위) → R2(여기) → community_signal(applySuspicionWeight)
        if (R2_ENABLED
                && image != null && "ai".equals(image.label)
                && image.confidence >= R2_IMAGE_CONF_THRESHOLD
                && R2_RISK_CATEGORIES.contains(category)
                && "uncertain".equals(m.label)) {

            boolean wasFinanceDowngraded = !m.label.equals(labelBeforeFinance); // finance가 label을 바꿨는가
            if (!wasFinanceDowngraded || R2_ALLOW_FINANCE_CHAIN) {
                m.preR2Label = labelBeforeFinance;  // 체인 시 원래 라벨(e.g. "true"), 직접 시 "uncertain"
                m.label      = "likely_false";
                double cur = m.confidence != null ? m.confidence : 0.5;
                m.confidence = Math.min(cur, 0.60);
                if (m.rulesFired == null) m.rulesFired = new ArrayList<>();
                m.rulesFired.add("R2:ai_risk_uncertain_escalation");
                if (wasFinanceDowngraded) m.rulesFired.add("R2:finance_chain");
            }
        }

        // claims 빌드
        m.claims = buildClaims(info, facts);
        return m;
    }

    private String dominantFactCheckRating(List<FactCheckResult> facts) {
        if (facts == null || facts.isEmpty()) return null;
        for (FactCheckResult fc : facts) {
            if (fc.matches != null) {
                for (FactCheckResult.Match match : fc.matches) {
                    String r = match.ratingNormalized;
                    if (r != null && !r.equals("unknown")) return r;
                }
            }
        }
        return null;
    }

    private List<AnalyzeResponse.ClaimWithFact> buildClaims(
            InfoResult info, List<FactCheckResult> facts) {
        List<AnalyzeResponse.ClaimWithFact> result = new ArrayList<>();
        List<InfoResult.Claim> claims = info.claims != null ? info.claims : List.of();
        for (int i = 0; i < claims.size(); i++) {
            InfoResult.Claim c = claims.get(i);
            AnalyzeResponse.ClaimWithFact cwf = new AnalyzeResponse.ClaimWithFact();
            cwf.text           = c.text;
            cwf.llmJudgement   = c.preliminaryJudgement;
            cwf.factcheckMatches = (facts != null && i < facts.size() && facts.get(i).matches != null)
                    ? facts.get(i).matches : List.of();
            cwf.supportingSources = (c.supportingSources != null)
                    ? c.supportingSources.stream().limit(3).collect(java.util.stream.Collectors.toList()) : List.of();
            cwf.axisAVerdict   = c.axisAVerdict;   // R3: null when 2-axis not used
            cwf.axisBVerdict   = c.axisBVerdict;
            cwf.drivingAxis    = c.drivingAxis;
            result.add(cwf);
        }
        return result;
    }

    /** 집단지성 가중치 결합 — aggregate() 호출 후 적용. suspicious_count 기준으로 threshold 판단 */
    public void applySuspicionWeight(AnalyzeResponse.Verdict v, int okCount, int suspiciousCount, String category, String lang) {
        final int LOW  = 5;
        final int HIGH = 20;

        AnalyzeResponse.CommunitySignal cs = new AnalyzeResponse.CommunitySignal();
        cs.okCount         = okCount;
        cs.suspiciousCount = suspiciousCount;

        if (suspiciousCount >= HIGH) {
            cs.thresholdReached = "high";
            if (v.misinformation != null && v.misinformation.confidence != null) {
                v.misinformation.confidence = Math.min(1.0, v.misinformation.confidence + 0.20);
                if ("uncertain".equals(v.misinformation.label))
                    v.misinformation.label = "likely_false";
            }
            if (v.aiGenerated != null)
                v.aiGenerated.confidence = Math.min(1.0, v.aiGenerated.confidence + 0.10);
        } else if (suspiciousCount >= LOW) {
            cs.thresholdReached = "low";
            if (v.misinformation != null && v.misinformation.confidence != null) {
                v.misinformation.confidence = Math.min(1.0, v.misinformation.confidence + 0.10);
                if ("uncertain".equals(v.misinformation.label))
                    v.misinformation.label = "likely_false";
            }
        } else {
            cs.thresholdReached = "none";
        }

        v.communitySignal = cs;
        v.displayMessage  = buildMessage(v.aiGenerated, v.misinformation, v.advertisement, cs, category, lang);
    }

    /** §9.4 display_message 규칙 표 */
    private String buildMessage(AnalyzeResponse.AiGenerated ai,
                                AnalyzeResponse.Misinformation mis,
                                AnalyzeResponse.Advertisement adv,
                                AnalyzeResponse.CommunitySignal cs,
                                String category,
                                String lang) {
        boolean isAi      = ai  != null && ai.label.equals("ai");
        boolean isFalse   = mis != null && mis.applicable
                && mis.label != null
                && (mis.label.equals("false") || mis.label.equals("likely_false"));
        boolean isScam    = adv != null && adv.applicable && "likely_scam".equals(adv.label);
        boolean isFalseAd = adv != null && adv.applicable && "likely_false_ad".equals(adv.label);
        boolean communityHigh = cs != null && "high".equals(cs.thresholdReached);
        boolean isFinance = "finance".equals(category);

        String base;
        if ("en".equals(lang)) {
            if (isScam)               base = "This content appears to be a scam";
            else if (isFalseAd)       base = "This content may contain false or exaggerated advertising";
            else if (isAi && isFalse) base = "This appears to be AI-generated and may contain false information";
            else if (isAi)            base = "This appears to be AI-generated content";
            else if (isFalse)         base = "This content may contain false information";
            else if (isFinance)       base = "This is stock/investment content. Always verify before making any investment decisions";
            else                      base = "No significant warning signs detected";
            if (communityHigh)        base = base + " · Many users have flagged this as suspicious";
        } else {
            if (isScam)               base = "사기가 의심되는 콘텐츠예요";
            else if (isFalseAd)       base = "허위·과대 광고가 의심돼요";
            else if (isAi && isFalse) base = "기계가 만든 영상이고, 사실과 다를 수 있어요";
            else if (isAi)            base = "기계가 만든 영상이에요";
            else if (isFalse)         base = "사실과 다를 수 있어요";
            else if (isFinance)       base = "주식·투자 관련 콘텐츠예요. 투자 전 반드시 직접 확인하세요";
            else                      base = "특별한 위험 신호가 없어요";
            if (communityHigh)        base = base + " · 많은 분들이 의심하고 있어요";
        }
        return base;
    }
}
