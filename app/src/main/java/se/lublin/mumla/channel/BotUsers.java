/*
 * Shared helper: is this username one of the server-side bookkeeping
 * bots (PTTAdmin / PTTWeather / PTTPhone-N)? Mirrors
 * server/murmur/client.py::_is_bot_username so the app's channel
 * user list and the dashboard's are the same humans-only view.
 */

package se.lublin.mumla.channel;

import java.util.List;

import se.lublin.humla.model.IUser;

public final class BotUsers {
    private BotUsers() {}

    public static boolean isBot(String name) {
        if (name == null) return false;
        if ("PTTAdmin".equals(name)
                || "PTTWeather".equals(name)
                || "PTTPhone".equals(name)) return true;
        return name.startsWith("PTTPhone-");
    }

    public static boolean isBot(IUser user) {
        return user != null && isBot(user.getName());
    }

    /** Count non-bot users in the given list; null-safe. */
    public static int countHumans(List<? extends IUser> users) {
        if (users == null) return 0;
        int n = 0;
        for (IUser u : users) if (!isBot(u)) n++;
        return n;
    }
}
