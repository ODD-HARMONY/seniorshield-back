package com.seniorshield.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnalyzeResponse {
    @JsonProperty("job_id")          public String jobId;
    @JsonProperty("video_id")        public String videoId;
    public boolean cached;
    public String lang;
    public Verdict verdict;
    public Map<String, StageStatus> stages;
    @JsonProperty("elapsed_ms_total") public long elapsedMsTotal;

    // error fields (used only on error responses)
    public String error;
    public String detail;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Verdict {
        @JsonProperty("ai_generated")      public AiGenerated   aiGenerated;
        public Misinformation              misinformation;
        public Advertisement               advertisement;
        @JsonProperty("community_signal")  public CommunitySignal communitySignal;
        @JsonProperty("display_message")   public String          displayMessage;
        public String                      category;  // classify 카테고리 (health/finance/politics/...)
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CommunitySignal {
        @JsonProperty("ok_count")          public int    okCount;
        @JsonProperty("suspicious_count")  public int    suspiciousCount;
        @JsonProperty("threshold_reached") public String thresholdReached;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AiGenerated {
        public String label;
        public double confidence;
        public String evidence;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Misinformation {
        public boolean applicable;
        public String label;
        public Double confidence;
        public List<ClaimWithFact> claims;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Advertisement {
        public boolean applicable;
        public String label;       // normal_ad | likely_false_ad | likely_scam
        public Double confidence;
        public String reason;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ClaimWithFact {
        public String text;
        @JsonProperty("llm_judgement")      public String llmJudgement;
        @JsonProperty("factcheck_matches")  public List<FactCheckResult.Match> factcheckMatches;
        @JsonProperty("supporting_sources") public List<String> supportingSources;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StageStatus {
        public boolean ok;
        @JsonProperty("elapsed_ms")    public long elapsedMs;
        public String informational;  // classify 단계에서만 사용 (String "true"/"false")
        public String error;
    }
}
