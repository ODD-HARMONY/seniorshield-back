package com.seniorshield.pipeline;

import com.seniorshield.model.*;

import java.util.ArrayList;
import java.util.List;

/** §9: 최종 verdict 생성 규칙 */
public class ResultAggregator {

    public AnalyzeResponse.Verdict aggregate(
            ClassifyResult classify,
            InfoResult info,
            ImageResult image,
            List<FactCheckResult> facts,
            String lang) {

        AnalyzeResponse.Verdict v = new AnalyzeResponse.Verdict();

        // §9.1 AI 생성 판별
        v.aiGenerated = buildAiGenerated(image);

        // §9.2 허위정보 판별
        v.misinformation = buildMisinformation(classify, info, facts);

        // Advertisement 항상 false (현재 미구현)
        v.advertisement = new AnalyzeResponse.Advertisement();
        v.advertisement.applicable = false;

        // §9.3 display_message (communitySignal은 applySuspicionWeight에서 채워지므로 null 전달)
        v.displayMessage = buildMessage(v.aiGenerated, v.misinformation, null, lang);

        return v;
    }

    private AnalyzeResponse.AiGenerated buildAiGenerated(ImageResult image) {
        AnalyzeResponse.AiGenerated ag = new AnalyzeResponse.AiGenerated();
        if (image != null) {
            ag.label      = image.label;
            ag.confidence = image.confidence;
            ag.evidence   = image.aggregateEvidence;
        } else {
            ag.label      = "uncertain";
            ag.confidence = 0.0;
            ag.evidence   = null;
        }
        return ag;
    }

    private AnalyzeResponse.Misinformation buildMisinformation(
            ClassifyResult classify, InfoResult info, List<FactCheckResult> facts) {

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
                m.confidence = Math.min(info.confidence, 0.7);
            }
        } else {
            // 팩트체크 매칭 없음 → LLM 결과만, confidence 최대 0.7
            m.label      = info.overallJudgement;
            m.confidence = Math.min(info.confidence, 0.7);
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
            result.add(cwf);
        }
        return result;
    }

    /** 집단지성 가중치 결합 — aggregate() 호출 후 적용. suspicious_count 기준으로 threshold 판단 */
    public void applySuspicionWeight(AnalyzeResponse.Verdict v, int okCount, int suspiciousCount, String lang) {
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
        v.displayMessage  = buildMessage(v.aiGenerated, v.misinformation, cs, lang);
    }

    /** §9.3 display_message 규칙 표 */
    private String buildMessage(AnalyzeResponse.AiGenerated ai,
                                AnalyzeResponse.Misinformation mis,
                                AnalyzeResponse.CommunitySignal cs,
                                String lang) {
        boolean isAi    = ai  != null && (ai.label.equals("ai") || ai.label.equals("likely_ai"));
        boolean isFalse = mis != null && mis.applicable
                && mis.label != null
                && (mis.label.equals("false") || mis.label.equals("likely_false"));
        boolean communityHigh = cs != null && "high".equals(cs.thresholdReached);

        String base;
        if ("en".equals(lang)) {
            if (isAi && isFalse) base = "This appears to be AI-generated and may contain false information";
            else if (isAi)       base = "This appears to be AI-generated content";
            else if (isFalse)    base = "This content may contain false information";
            else                 base = "No significant warning signs detected";
            if (communityHigh)   base = base + " · Many users have flagged this as suspicious";
        } else {
            if (isAi && isFalse) base = "기계가 만든 영상이고, 사실과 다를 수 있어요";
            else if (isAi)       base = "기계가 만든 영상이에요";
            else if (isFalse)    base = "사실과 다를 수 있어요";
            else                 base = "특별한 위험 신호가 없어요";
            if (communityHigh)   base = base + " · 많은 분들이 의심하고 있어요";
        }
        return base;
    }
}
