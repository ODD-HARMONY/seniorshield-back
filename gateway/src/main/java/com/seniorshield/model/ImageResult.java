package com.seniorshield.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ImageResult {
    @JsonProperty("per_frame") public List<FrameResult> perFrame;
    public String label;
    public double confidence;
    @JsonProperty("aggregate_evidence") public String aggregateEvidence;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FrameResult {
        public int index;
        public String label;
        public double confidence;
        public String evidence;
    }
}
