package com.seniorshield.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ClassifyResult {
    public boolean informational;
    public String category;
    @JsonProperty("key_topic") public String keyTopic;
    public String reason;
}
