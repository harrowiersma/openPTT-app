/*
 * Shared helper: is this channel one the human operator should see
 * via the carousel UI / knob navigation / on-join TTS? The Phone
 * tree is now overlay-only — the parent gives no value (the overlay
 * drops users straight into Phone/Call-N) and Call-N sub-channels
 * are reached only via that overlay too.
 *
 * Both ChannelCarouselFragment and MumlaService.switchChannel must
 * use this to stay in sync; otherwise the carousel can hide a
 * channel that the knob still navigates to (and TTS announces).
 */

package se.lublin.mumla.channel;

import se.lublin.humla.model.IChannel;

public final class HumanChannels {
    private HumanChannels() {}

    public static boolean isVisible(IChannel c) {
        if (c == null) return false;
        return isVisible(c.getName());
    }

    public static boolean isVisible(String name) {
        if (name == null) return false;
        if ("Phone".equals(name)) return false;
        if (name.startsWith("Call-")) return false;
        return true;
    }
}
