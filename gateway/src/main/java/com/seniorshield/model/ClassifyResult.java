package com.seniorshield.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ClassifyResult {
    public boolean informational;
    public String category;
    @JsonProperty("key_topic") public String keyTopic;
    @JsonProperty("key_claim") public String keyClaim;
    public boolean advertisement;
    @JsonProperty("ad_label") public String adLabel;  // none | normal_ad | likely_false_ad | likely_scam

    /** Used when subtitle is unavailable — skips classify API call entirely. */
    public static ClassifyResult notApplicable() {
        ClassifyResult r = new ClassifyResult();
        r.informational = false;
        r.category      = "unknown";
        return r;
    }
}
