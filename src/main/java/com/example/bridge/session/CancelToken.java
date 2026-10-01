package com.example.bridge.session;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cancellation token for one running query. A new token is installed when a query starts
 * and cleared when it ends, so late or wrong-key cancels cannot affect later queries.
 */
public final class CancelToken {

    private final int secret;
    private final Runnable cancelAction;
    private final AtomicBoolean fired = new AtomicBoolean(false);

    public CancelToken(int secret, Runnable cancelAction) {
        this.secret = secret;
        this.cancelAction = cancelAction;
    }

    public boolean secretMatches(int candidate) {
        return secret == candidate;
    }

    public boolean fire() {
        if (fired.compareAndSet(false, true)) {
            cancelAction.run();
            return true;
        }
        return false;
    }
}
