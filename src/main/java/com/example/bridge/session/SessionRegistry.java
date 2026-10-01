package com.example.bridge.session;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Routes CancelRequest (pid + secret) to the owning session. */
public class SessionRegistry {
    private final Map<Integer, Session> sessions = new ConcurrentHashMap<>();

    public void register(Session s) {
        sessions.put(s.getPid(), s);
    }

    public void unregister(Session s) {
        sessions.remove(s.getPid(), s);
    }

    /** Wrong pid or secret is a no-op, as required by the protocol. */
    public void cancel(int pid, int secret) {
        Session s = sessions.get(pid);
        if (s != null && s.getSecret() == secret) {
            s.cancelCurrentQuery();
        }
    }
}

