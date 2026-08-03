package com.ep.cache;

public class InvalidateMsg {
    private String cacheName;
    private String key;

    public InvalidateMsg() {}

    public InvalidateMsg(String cacheName, String key) {
        this.cacheName = cacheName;
        this.key = key;
    }

    public String getCacheName() { return cacheName; }
    public String getKey() { return key; }
    public void setCacheName(String c) { this.cacheName = c; }
    public void setKey(String k) { this.key = k; }
}
