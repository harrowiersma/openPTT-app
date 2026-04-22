/*
 * Shared helper: is this channel one the human operator should see
 * via the carousel UI / knob navigation / on-join TTS? The Phone
 * tree is overlay-only — the parent gives no value (the overlay
 * drops users straight into Phone/Call-N) and Call-N sub-channels
 * are reached only via that overlay too.
 *
 * Server-side call-groups can also deny Traverse on a channel via
 * Mumble ACL. When the server has answered our PermissionQuery for
 * a channel and the response says Traverse is not granted, we hide
 * the channel from the carousel + knob rotation the same way we
 * hide Phone.
 *
 * Both ChannelCarouselFragment and MumlaService.switchChannel must
 * use this to stay in sync; otherwise the carousel can hide a
 * channel that the knob still navigates to (and TTS announces).
 */

package se.lublin.mumla.channel;

import se.lublin.humla.model.IChannel;
import se.lublin.humla.net.Permissions;

public final class HumanChannels {
    private HumanChannels() {}

    public static boolean isVisible(IChannel c) {
        if (c == null) return false;
        if (!isVisible(c.getName())) return false;
        // Traverse check: only filter when the server has actually
        // answered our PermissionQuery for this channel (Cached bit is
        // set in the response). Before that we'd have 0 for every
        // channel on initial connect and hide them all.
        int perms = c.getPermissions();
        if ((perms & Permissions.Cached) != 0) {
            return (perms & Permissions.Traverse) != 0;
        }
        return true;
    }

    public static boolean isVisible(String name) {
        if (name == null) return false;
        if ("Phone".equals(name)) return false;
        if (name.startsWith("Call-")) return false;
        return true;
    }
}
