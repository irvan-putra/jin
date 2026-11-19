package com.vanipy.jin.transport;

/** Constants for the Jin cross-app transport contract v1. See docs/transport-contract.md. */
public final class TransportContract {
    private TransportContract() {}
    public static final int VERSION = 1;
    public static final String AUTHORITY = "com.vanipy.jin.transport";
    public static final long DEFAULT_MAX_BYTES = 20L * 1024 * 1024; // relay limit can tighten at runtime
    public static final long STAGE_TOKEN_TTL_MS = 10L * 60 * 1000;
    public static final long MAX_FUTURE_SKEW_MS = 5L * 60 * 1000;
    public static final String TOKEN_PATTERN = "[A-Za-z0-9_\\-]{8,64}";

    public static final String M_CAPABILITIES = "capabilities";
    public static final String M_LIST_CHATS = "listChats";
    public static final String M_STAGE = "stage";
    public static final String M_COMMIT = "commit";
    public static final String M_CANCEL = "cancel";
    public static final String M_STATUS = "status";
    public static final String M_POLL = "poll";

    public static final String K_OK = "ok";
    public static final String K_ERROR = "error";
    public static final String K_CONTRACT_VERSION = "contract_version";
    public static final String K_MAX_BYTES = "max_bytes";
    public static final String K_GRANTED = "granted";
    public static final String K_PREFIX = "prefix";
    public static final String K_URI = "uri";
    public static final String K_REF = "ref";
    public static final String K_STATUS = "status";
    public static final String K_META = "meta";
    public static final String K_ITEMS = "items";
    public static final String K_CURSOR = "cursor";
    public static final String K_LIMIT = "limit";

    public static final String E_NOT_AUTHORIZED = "NOT_AUTHORIZED";
    public static final String E_BAD_TOKEN = "BAD_TOKEN";
    public static final String E_TOKEN_REUSED = "TOKEN_REUSED";
    public static final String E_FILE_TOO_LARGE = "FILE_TOO_LARGE";
    public static final String E_BOT_NOT_RUNNING = "BOT_NOT_RUNNING";
    public static final String E_TIMEOUT = "TIMEOUT";
    public static final String E_MALFORMED = "MALFORMED";
    public static final String E_UNKNOWN_METHOD = "UNKNOWN_METHOD";
    public static final String E_FUTURE_TS = "FUTURE_TIMESTAMP";
    public static final String E_CHAT_NOT_GRANTED = "CHAT_NOT_GRANTED";

    public static final String S_ACCEPTED = "ACCEPTED_BY_JIN";
    public static final String S_QUEUED = "QUEUED";
    public static final String S_SENT_TO_RELAY = "SENT_TO_RELAY";
    public static final String S_FAILED = "FAILED";
    public static final String S_EXPIRED = "EXPIRED";
    public static final String S_READ = "READ";
}
