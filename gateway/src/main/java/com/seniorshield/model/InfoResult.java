package com.seniorshield.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class InfoResult {
    public List<Claim> claims;
    @JsonProperty("overall_judgement") public String overallJudgement;
    public double confidence;
    public String reasoning;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Claim {
        public String text;
        public String normalized;
        @JsonProperty("preliminary_judgement") public String preliminaryJudgement;
        @JsonProperty("supporting_sources")    public List<String> supportingSources;
    }
}
