/*
 * Hides Offline-status users from the channel user list. Mirrors
 * BotUsers in shape and intent: pure static helpers, null-safe,
 * called from UserRowAdapter.submit and the channel-card / carousel
 * member counters.
 *
 * "Hidden" means the user marked themself Offline AND they aren't the
 * local device's own user. NULL/unknown status fails open (visible) so
 * a transient cache miss never makes a user vanish unexpectedly.
 */
package se.lublin.mumla.channel;

import java.util.List;

import se.lublin.humla.model.IUser;

public final class PresenceFilter {
    private PresenceFilter() {}

    public static boolean isHidden(IUser user, PresenceCache cache, String selfName) {
        if (user == null || cache == null) return false;
        String name = user.getName();
        if (name == null) return false;
        if (selfName != null && name.equalsIgnoreCase(selfName)) return false;
        return "offline".equals(cache.getStatus(name));
    }

    public static int countVisible(
            List<? extends IUser> users, PresenceCache cache, String selfName) {
        if (users == null) return 0;
        int n = 0;
        for (IUser u : users) {
            if (u == null) continue;
            if (BotUsers.isBot(u)) continue;
            if (isHidden(u, cache, selfName)) continue;
            n++;
        }
        return n;
    }
}
