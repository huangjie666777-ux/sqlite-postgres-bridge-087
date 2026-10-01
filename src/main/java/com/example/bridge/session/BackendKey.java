package com.example.bridge.session;

/** (PID, secret) pair used in BackendKeyData and CancelRequest. */
public record BackendKey(int pid, int secret) {
}
