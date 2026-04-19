package se.lublin.mumla.service;

import java.util.List;

import se.lublin.humla.IHumlaService;

/**
 * Created by andrew on 28/02/17.
 */
public interface IMumlaService extends IHumlaService {
    void setOverlayShown(boolean showOverlay);

    boolean isOverlayShown();

    void clearChatNotifications();

    void markErrorShown();

    boolean isErrorShown();

    void onTalkKeyDown();

    void onTalkKeyUp();

    List<IChatMessage> getMessageLog();

    void clearMessageLog();

    void setSuppressNotifications(boolean suppressNotifications);

    /**
     * Switch to the next or previous channel using the rotary knob.
     * @param direction +1 for next (F6/clockwise), -1 for previous (F5/counter-clockwise)
     */
    void switchChannel(int direction);

    String currentChannelName();

    void phoneMuteToggle();

    void phoneHangup();
}
