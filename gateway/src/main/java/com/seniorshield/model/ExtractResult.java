package com.seniorshield.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ExtractResult {
    @JsonProperty("job_id")      public String jobId;
    @JsonProperty("video_id")    public String videoId;
    @JsonProperty("duration_sec") public Double durationSec;
    @JsonProperty("title")       public String title;
    @JsonProperty("subtitle")    public SubtitleInfo subtitle;
    @JsonProperty("frames")      public FramesInfo frames;
    @JsonProperty("elapsed_ms")  public Object elapsedMs;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SubtitleInfo {
        public boolean available;
        public String source;
        public String language;
        public String text;
        @JsonProperty("char_count")        public Integer charCount;
        @JsonProperty("segment_count")     public Integer segmentCount;
        public String reason;
        @JsonProperty("available_langs")   public AvailableLangs availableLangs;
        @JsonProperty("original_language") public String originalLanguage;
        @JsonProperty("selection_reason")  public String selectionReason;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AvailableLangs {
        public java.util.List<String> manual;
        public java.util.List<String> auto;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FramesInfo {
        public int count;
        @JsonProperty("frames_base64")   public List<String> framesBase64;
        @JsonProperty("timestamps_sec")  public List<Double> timestampsSec;
    }
}
