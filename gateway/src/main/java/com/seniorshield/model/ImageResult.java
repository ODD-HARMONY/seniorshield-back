package com.seniorshield.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ImageResult {
    @JsonProperty("per_frame") public List<FrameResult> perFrame;
    public String label;
    public double confidence;

    @JsonProperty("revision_notes")
    public String revisionNotes;

    @JsonProperty("_internal")
    public InternalDebug internal;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FrameResult {
        public int index;
        public String label;
        public double confidence;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InternalDebug {
        @JsonProperty("round1_observation")
        public String round1Observation;
    }
}
