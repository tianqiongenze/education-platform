package com.ep.api.dubbo;
import java.io.Serializable;
public class HealthStatus implements Serializable {
    private static final long serialVersionUID = 1L;
    private boolean healthy; private String service; private long timestamp;
    public HealthStatus() {}
    public HealthStatus(boolean h, String s, long t) { healthy=h; service=s; timestamp=t; }
    public boolean isHealthy() { return healthy; }
    public String getService() { return service; }
    public long getTimestamp() { return timestamp; }
    public void setHealthy(boolean h){this.healthy=h;}
    public void setService(String s){this.service=s;}
    public void setTimestamp(long t){this.timestamp=t;}
}
