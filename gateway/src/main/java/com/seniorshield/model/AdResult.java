package com.seniorshield.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AdResult {
    public String label;       // normal_ad | likely_false_ad | likely_scam
    public double confidence;
    public String reason;
}
