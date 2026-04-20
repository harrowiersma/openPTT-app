/*
 * Parser for the admin server's INCOMING_CALL text whisper.
 *
 * Format: "INCOMING_CALL|<caller_id>|<sub_channel>"
 *   - caller_id is the free-form phone number string from DIDWW
 *   - sub_channel is a Phone child channel name, e.g. "Call-1"
 *
 * Delivered as a user-targeted whisper (not channel chat) by the
 * admin's /internal/call-assigned hook after the SIP bridge acquires
 * a slot. Receiving this raises the full-screen IncomingCallActivity;
 * the message must NOT also render in the chat log.
 */

package se.lublin.mumla.phone;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class IncomingCallParser {
    public static final String PREFIX = "INCOMING_CALL|";

    public static final class Result {
        public final String callerId;
        public final String subChannel;

        Result(String callerId, String subChannel) {
            this.callerId = callerId;
            this.subChannel = subChannel;
        }
    }

    /**
     * Return a Result if the message matches "INCOMING_CALL|caller|sub",
     * otherwise null. The caller-id and sub-channel segments must both
     * be non-empty to count as a valid payload — an empty segment is
     * treated as malformed rather than silently mapped to "".
     */
    @Nullable
    public static Result parse(@Nullable String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (!trimmed.startsWith(PREFIX)) return null;
        String[] parts = trimmed.split("\\|", -1);
        if (parts.length != 3) return null;
        String caller = parts[1];
        String sub = parts[2];
        if (caller == null || caller.isEmpty()) return null;
        if (sub == null || sub.isEmpty()) return null;
        return new Result(caller, sub);
    }

    /** Convenience: does this text look like an INCOMING_CALL payload? */
    public static boolean looksLike(@NonNull String text) {
        return text.startsWith(PREFIX);
    }

    private IncomingCallParser() {}
}
