package com.supplymind.agent.llm;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded, request-lifetime cancellation registry; no jobs, reports or model state are retained. */
public final class AgentCancellation {
    private static final Map<String, AtomicBoolean> ACTIVE=new HashMap<>();
    private static final Map<String, Long> EARLY=new LinkedHashMap<>();
    private static final ThreadLocal<AtomicBoolean> CURRENT=new ThreadLocal<>();
    private AgentCancellation() {}
    public static synchronized void begin(String id) {
        validate(id); prune();
        if(ACTIVE.size()>=128 || ACTIVE.containsKey(id))throw new IllegalArgumentException("request token unavailable");
        var flag=new AtomicBoolean(EARLY.remove(id)!=null); ACTIVE.put(id,flag);CURRENT.set(flag);
    }
    public static synchronized void end(String id) { ACTIVE.remove(id);CURRENT.remove(); }
    public static synchronized void cancel(String id) {
        validate(id);prune();var flag=ACTIVE.get(id);
        if(flag!=null)flag.set(true);
        else { if(EARLY.size()>=128)EARLY.remove(EARLY.keySet().iterator().next()); EARLY.put(id,System.nanoTime()); }
    }
    static AtomicBoolean current() { var value=CURRENT.get();return value==null?new AtomicBoolean():value; }
    private static void validate(String id) { if(id==null || !id.matches("[A-Za-z0-9-]{16,80}"))throw new IllegalArgumentException("invalid request token"); }
    private static void prune() { long now=System.nanoTime(); EARLY.values().removeIf(at->now-at>60_000_000_000L); }
}
