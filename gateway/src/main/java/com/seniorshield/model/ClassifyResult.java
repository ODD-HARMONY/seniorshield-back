package com.seniorshield.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ClassifyResult {
    public boolean informational;
    public String category;
    @JsonProperty("key_topic") public String keyTopic;
    public String reason;

    /** Used when subtitle is unavailable — skips classify API call entirely. */
    public static ClassifyResult notApplicable() {
        ClassifyResult r = new ClassifyResult();
        r.informational = false;
        r.category      = "unknown";
        r.reason        = "no_subtitle";
        return r;
    }
}
