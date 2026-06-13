package com.seniorshield.cache;

public class CacheEntry {
    public final String resultJson;
    public final int    hitCount;

    public CacheEntry(String resultJson, int hitCount) {
        this.resultJson = resultJson;
        this.hitCount   = hitCount;
    }
}
