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
            List<FactCheckResult> facts) {

        AnalyzeResponse.Verdict v = new AnalyzeResponse.Verdict();

        // §9.1 AI 생성 판별
        v.aiGenerated = buildAiGenerated(image);

        // §9.2 허위정보 판별
        v.misinformation = buildMisinformation(classify, info, facts);

        // Advertisement 항상 false (현재 미구현)
        v.advertisement = new AnalyzeResponse.Advertisement();
        v.advertisement.applicable = false;

        // §9.3 display_message
        v.displayMessage = buildMessage(v.aiGenerated, v.misinformation);

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

    /** §3: 집단지성 가중치 결합 — aggregate() 호출 후 적용 */
    public void applySuspicionWeight(AnalyzeResponse.Verdict v, int count) {
        final int LOW  = 5;
        final int HIGH = 20;

        AnalyzeResponse.CommunitySignal cs = new AnalyzeResponse.CommunitySignal();
        cs.suspicionCount = count;

        if (count >= HIGH) {
            cs.thresholdReached = "high";
            if (v.misinformation != null && v.misinformation.confidence != null) {
                v.misinformation.confidence = Math.min(1.0, v.misinformation.confidence + 0.20);
                if ("uncertain".equals(v.misinformation.label))
                    v.misinformation.label = "likely_false";
            }
            if (v.aiGenerated != null)
                v.aiGenerated.confidence = Math.min(1.0, v.aiGenerated.confidence + 0.10);
        } else if (count >= LOW) {
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
        // display_message 재계산 (가중치 반영 후)
        v.displayMessage = buildMessage(v.aiGenerated, v.misinformation);
    }

    /** §9.3 display_message 규칙 표 */
    private String buildMessage(AnalyzeResponse.AiGenerated ai,
                                AnalyzeResponse.Misinformation mis) {
        boolean isAi    = ai  != null && (ai.label.equals("ai") || ai.label.equals("likely_ai"));
        boolean isFalse = mis != null && mis.applicable
                && mis.label != null
                && (mis.label.equals("false") || mis.label.equals("likely_false"));

        if (isAi && isFalse) return "기계가 만든 영상이고, 사실과 다를 수 있어요";
        if (isAi)            return "기계가 만든 영상이에요";
        if (isFalse)         return "사실과 다를 수 있어요";
        return "특별한 위험 신호가 없어요";
    }
}
