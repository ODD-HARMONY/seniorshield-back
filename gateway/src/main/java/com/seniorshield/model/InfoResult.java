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
        // R3 2축 판정 필드 (2axis 프롬프트 사용 시 채워짐, 그 외 null)
        @JsonProperty("axis_a_verdict")        public String axisAVerdict;
        @JsonProperty("axis_b_verdict")        public String axisBVerdict;
        @JsonProperty("driving_axis")          public String drivingAxis;
    }
}
