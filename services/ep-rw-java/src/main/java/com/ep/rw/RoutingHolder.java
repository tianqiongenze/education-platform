package com.ep.rw;
public final class RoutingHolder {
    private static final ThreadLocal<DataSourceType> CTX = new ThreadLocal<>();
    private RoutingHolder() {}
    public static void set(DataSourceType t) { CTX.set(t); }
    public static DataSourceType get() { return CTX.get(); }
    public static void clear() { CTX.remove(); }
}
