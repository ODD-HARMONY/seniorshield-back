package com.seniorshield.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class FactCheckResult {
    public List<Match> matches;
    @JsonProperty("language_used")    public String  languageUsed;
    @JsonProperty("fallback_applied") public boolean fallbackApplied;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Match {
        public String publisher;
        @JsonProperty("publisher_site")    public String publisherSite;
        public String rating;
        @JsonProperty("rating_normalized") public String ratingNormalized;
        public String url;
        @JsonProperty("review_date")       public String reviewDate;
    }
}
