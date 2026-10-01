package com.example.bridge.protocol;

import java.util.Map;

/** Decoded frontend messages produced by {@link PgFrameDecoder}. */
public sealed interface FrontendMessage {

    record Startup(Map<String, String> parameters) implements FrontendMessage {
    }

    record SslProbe() implements FrontendMessage {
    }

    record Cancel(int pid, int secret) implements FrontendMessage {
    }

    record Query(String sql) implements FrontendMessage {
    }

    record Terminate() implements FrontendMessage {
    }

    /** Extended-protocol messages (Parse/Bind/Execute/Describe/Sync/Flush/Close): rejected. */
    record Unsupported(char type) implements FrontendMessage {
    }

    /** A length/format rule was broken; connection must be torn down. */
    record ProtocolViolation(String detail) implements FrontendMessage {
    }
}
