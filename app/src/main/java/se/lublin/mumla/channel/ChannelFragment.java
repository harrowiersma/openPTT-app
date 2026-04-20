/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.channel;

import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.preference.PreferenceManager;
import androidx.viewpager.widget.PagerTabStrip;
import androidx.viewpager.widget.ViewPager;

import java.util.ArrayList;
import java.util.List;

import java.util.Collections;
import java.util.Comparator;

import se.lublin.humla.HumlaService;
import se.lublin.humla.IHumlaService;
import se.lublin.humla.IHumlaSession;
import se.lublin.humla.model.IChannel;
import se.lublin.humla.model.IUser;
import se.lublin.humla.model.WhisperTarget;
import se.lublin.humla.util.HumlaDisconnectedException;
import se.lublin.humla.util.HumlaObserver;
import se.lublin.humla.util.IHumlaObserver;
import se.lublin.humla.util.VoiceTargetMode;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.util.HumlaServiceFragment;

/**
 * Class to encapsulate both a ChannelListFragment and ChannelChatFragment.
 * Created by andrew on 02/08/13.
 */
public class ChannelFragment extends HumlaServiceFragment implements SharedPreferences.OnSharedPreferenceChangeListener, ChatTargetProvider {
    private static final String TAG = ChannelFragment.class.getName();

    private ViewPager mViewPager;
    private PagerTabStrip mTabStrip;
    private Button mTalkButton;
    private View mTalkView;

    /** Softkey labels flanking the PTT button — updated to show the
     *  destination channel name ("PREV\nEmergency") on every own-session
     *  channel change. */
    private TextView mSoftkeyLeft;
    private TextView mSoftkeyRight;

    private View mTargetPanel;
    private ImageView mTargetPanelCancel;
    private TextView mTargetPanelText;

    private ChatTarget mChatTarget;
    /** Chat target listeners, notified when the chat target is changed. */
    private List<OnChatTargetSelectedListener> mChatTargetListeners = new ArrayList<OnChatTargetSelectedListener>();

    /** True iff the talk button has been hidden (e.g. when muted) */
    private boolean mTalkButtonHidden;

    private HumlaObserver mObserver = new HumlaObserver() {
        @Override
        public void onUserTalkStateUpdated(IUser user) {
            if (getService() == null || !getService().isConnected()) {
                return;
            }
            int selfSession;
            try {
                selfSession = getService().HumlaSession().getSessionId();
            } catch (HumlaDisconnectedException|IllegalStateException e) {
                Log.d(TAG, "exception in onUserTalkStateUpdated: " + e);
                return;
            }
            if (user != null && user.getSession() == selfSession) {
                // Manually set button selection colour when we receive a talk state update.
                // This allows representation of talk state when using hot corners and PTT toggle.
                switch (user.getTalkState()) {
                case TALKING:
                case SHOUTING:
                case WHISPERING:
                    mTalkButton.setPressed(true);
                    break;
                case PASSIVE:
                    mTalkButton.setPressed(false);
                    break;
                }
            }
        }

        @Override
        public void onUserStateUpdated(IUser user) {
            if (getService() == null || !getService().isConnected()) {
                return;
            }
            int selfSession;
            try {
                selfSession = getService().HumlaSession().getSessionId();
            } catch (IllegalStateException e) {
                Log.d(TAG, "exception in onUserStateUpdated: " + e);
                return;
            }
            if (user != null && user.getSession() == selfSession) {
                configureInput();
            }
        }

        @Override
        public void onVoiceTargetChanged(VoiceTargetMode mode) {
            configureTargetPanel();
        }

        @Override
        public void onUserJoinedChannel(IUser user, IChannel newChannel, IChannel oldChannel) {
            if (getService() == null || !getService().isConnected()) return;
            try {
                int selfSession = getService().HumlaSession().getSessionId();
                if (user != null && user.getSession() == selfSession) {
                    updateSoftkeyLabels();
                }
            } catch (IllegalStateException e) {
                // session not yet synchronized — labels will refresh on
                // onServiceBound.
            }
        }

        @Override
        public void onChannelAdded(IChannel channel) { updateSoftkeyLabels(); }
        @Override
        public void onChannelRemoved(IChannel channel) { updateSoftkeyLabels(); }
        @Override
        public void onChannelStateUpdated(IChannel channel) { updateSoftkeyLabels(); }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_channel, container, false);
        mViewPager = (ViewPager) view.findViewById(R.id.channel_view_pager);
        mTabStrip = (PagerTabStrip) view.findViewById(R.id.channel_tab_strip);
        if(mTabStrip != null) {
            int[] attrs = new int[] { android.R.attr.colorPrimary, android.R.attr.textColorPrimaryInverse };
            TypedArray a = getActivity().obtainStyledAttributes(attrs);
            int titleStripBackground = a.getColor(0, -1);
            int titleStripColor = a.getColor(1, -1);
            a.recycle();

            mTabStrip.setTextColor(titleStripColor);
            mTabStrip.setTabIndicatorColor(titleStripColor);
            mTabStrip.setBackgroundColor(titleStripBackground);
            mTabStrip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);

            // Hide tab strip on small screens (e.g. 240x320 P50) to save vertical space
            int screenSize = getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK;
            if (screenSize == Configuration.SCREENLAYOUT_SIZE_SMALL) {
                mTabStrip.setVisibility(View.GONE);
            }
        }

        mTalkView = view.findViewById(R.id.pushtotalk_view);
        mTalkButton = (Button) view.findViewById(R.id.pushtotalk);
        mTalkButton.setOnTouchListener(new View.OnTouchListener() {

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        if (getService() != null) {
                            getService().onTalkKeyDown();
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        if (getService() != null) {
                            getService().onTalkKeyUp();
                        }
                        break;
                }
                return true;
            }
        });
        mTargetPanel = view.findViewById(R.id.target_panel);
        mTargetPanelCancel = (ImageView) view.findViewById(R.id.target_panel_cancel);
        mTargetPanelCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (getService() == null || !getService().isConnected())
                    return;

                IHumlaSession session = getService().HumlaSession();
                if (session.getVoiceTargetMode() == VoiceTargetMode.WHISPER) {
                    byte target = session.getVoiceTargetId();
                    session.setVoiceTargetId((byte) 0);
                    session.unregisterWhisperTarget(target);
                }
            }
        });
        mTargetPanelText = (TextView) view.findViewById(R.id.target_panel_warning);
        mSoftkeyLeft = (TextView) view.findViewById(R.id.softkeyLeft);
        mSoftkeyRight = (TextView) view.findViewById(R.id.softkeyRight);
        configureInput();
        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
        preferences.registerOnSharedPreferenceChangeListener(this);

        if(mViewPager != null) { // Phone
            ChannelFragmentPagerAdapter pagerAdapter = new ChannelFragmentPagerAdapter(getChildFragmentManager());
            mViewPager.setAdapter(pagerAdapter);
        } else { // Tablet
            ChannelListFragment listFragment = new ChannelListFragment();
            Bundle listArgs = new Bundle();
            listArgs.putBoolean("pinned", isShowingPinnedChannels());
            listFragment.setArguments(listArgs);
            ChannelChatFragment chatFragment = new ChannelChatFragment();

            getChildFragmentManager().beginTransaction()
                    .replace(R.id.list_fragment, listFragment)
                    .replace(R.id.chat_fragment, chatFragment)
                    .commit();
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.channel_menu, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Settings settings = Settings.getInstance(getActivity());
        int itemId = item.getItemId();
        if (itemId == R.id.menu_input_voice) {
            settings.setInputMethod(Settings.ARRAY_INPUT_METHOD_VOICE);
            return true;
        } else if (itemId == R.id.menu_input_ptt) {
            settings.setInputMethod(Settings.ARRAY_INPUT_METHOD_PTT);
            return true;
        } else if (itemId == R.id.menu_input_continuous) {
            settings.setInputMethod(Settings.ARRAY_INPUT_METHOD_CONTINUOUS);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (getService() != null && getService().isConnected() &&
            !Settings.getInstance(getActivity()).isPushToTalkToggle()) {
            // XXX: This ensures that push to talk is disabled when we pause.
            // We don't want to leave the talk state active if the fragment is paused while pressed.
            getService().HumlaSession().setTalkingState(false);
        }
    }

    @Override
    public void onDestroy() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
        preferences.unregisterOnSharedPreferenceChangeListener(this);
        super.onDestroy();
    }

    @Override
    public IHumlaObserver getServiceObserver() {
        return mObserver;
    }

    @Override
    public void onServiceBound(IHumlaService service) {
        super.onServiceBound(service);
        if (service.getConnectionState() == HumlaService.ConnectionState.CONNECTED) {
            configureTargetPanel();
            configureInput();
            updateSoftkeyLabels();
        }
    }

    /**
     * Update the "◀ PREV / <name>" and "NEXT ▶ / <name>" labels flanking
     * the PTT button to show the channel the D-pad / knob will take the
     * user to. Matches the Root-children filter + sort used by
     * ChannelCarouselFragment so the labels agree with the tile strip.
     */
    private void updateSoftkeyLabels() {
        if (mSoftkeyLeft == null || mSoftkeyRight == null) return;
        String prevName = "";
        String nextName = "";
        try {
            if (getService() != null && getService().isConnected()) {
                IHumlaSession session = getService().HumlaSession();
                IChannel root = session.getRootChannel();
                if (root != null) {
                    List<? extends IChannel> kids = root.getSubchannels();
                    List<IChannel> filtered = new ArrayList<>();
                    if (kids != null) {
                        for (IChannel c : kids) {
                            String nm = c.getName() == null ? "" : c.getName();
                            if (!nm.startsWith("Call-")) filtered.add(c);
                        }
                    }
                    Collections.sort(filtered, new Comparator<IChannel>() {
                        @Override public int compare(IChannel a, IChannel b) {
                            String an = a.getName() == null ? "" : a.getName();
                            String bn = b.getName() == null ? "" : b.getName();
                            return an.compareToIgnoreCase(bn);
                        }
                    });
                    IChannel cur = session.getSessionChannel();
                    int curIdx = -1;
                    if (cur != null) {
                        for (int i = 0; i < filtered.size(); i++) {
                            if (filtered.get(i).getId() == cur.getId()) { curIdx = i; break; }
                        }
                    }
                    if (curIdx >= 0 && filtered.size() > 1) {
                        int prevIdx = (curIdx - 1 + filtered.size()) % filtered.size();
                        int nextIdx = (curIdx + 1) % filtered.size();
                        prevName = filtered.get(prevIdx).getName();
                        nextName = filtered.get(nextIdx).getName();
                    }
                }
            }
        } catch (IllegalStateException e) {
            // session unavailable; fall through with blank destinations
        }
        mSoftkeyLeft.setText(prevName.isEmpty() ? "◀ PREV" : "◀ PREV\n" + prevName);
        mSoftkeyRight.setText(nextName.isEmpty() ? "NEXT ▶" : "NEXT ▶\n" + nextName);
    }

    private void configureTargetPanel() {
        if (getService() == null || !getService().isConnected()) {
            return;
        }

        IHumlaSession session = getService().HumlaSession();
        VoiceTargetMode mode = session.getVoiceTargetMode();
        if (mode == VoiceTargetMode.WHISPER) {
            WhisperTarget target = session.getWhisperTarget();
            mTargetPanel.setVisibility(View.VISIBLE);
            mTargetPanelText.setText(getString(R.string.shout_target, target.getName()));
        } else {
            mTargetPanel.setVisibility(View.GONE);
        }
    }

    /**
     * @return true if the channel fragment is set to display only the user's pinned channels.
     */
    private boolean isShowingPinnedChannels() {
        return getArguments() != null &&
               getArguments().getBoolean("pinned");
    }

    /**
     * Configures the fragment in accordance with the user's interface preferences.
     */
    private void configureInput() {
        Settings settings = Settings.getInstance(getActivity());

        // The round MaterialButton has a fixed 56x56dp circle in XML —
        // don't override height or it becomes an ellipse. The former
        // pttButtonHeight preference only made sense for the rectangular
        // bar and is now unused by this layout.

        boolean muted = false;
        if (getService() != null && getService().isConnected()) {
            IUser self = null;
            try {
                self = getService().HumlaSession().getSessionUser();
            } catch (HumlaDisconnectedException|IllegalStateException e) {
                Log.d(TAG, "exception in configureInput: " + e);
            }
            muted = self == null || self.isMuted() || self.isSuppressed() || self.isSelfMuted();
        }
        boolean showPttButton =
                !muted &&
                settings.isPushToTalkButtonShown() &&
                settings.getInputMethod().equals(Settings.ARRAY_INPUT_METHOD_PTT);
        setTalkButtonHidden(!showPttButton);
    }

    private void setTalkButtonHidden(final boolean hidden) {
        // Hide the PTT button alone (not the whole row) so the Prev/Next
        // softkey labels stay put. INVISIBLE, not GONE — keep the slot so
        // Prev and Next don't re-center when the button comes and goes.
        mTalkButton.setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
        mTalkButtonHidden = hidden;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if(Settings.PREF_INPUT_METHOD.equals(key)
            || Settings.PREF_PUSH_BUTTON_HIDE_KEY.equals(key)
            || Settings.PREF_PTT_BUTTON_HEIGHT.equals(key))
            configureInput();
    }

    @Override
    public ChatTarget getChatTarget() {
        return mChatTarget;
    }

    @Override
    public void setChatTarget(ChatTarget target) {
        mChatTarget = target;
        for(OnChatTargetSelectedListener listener : mChatTargetListeners)
            listener.onChatTargetSelected(target);
    }

    @Override
    public void registerChatTargetListener(OnChatTargetSelectedListener listener) {
        mChatTargetListeners.add(listener);
    }

    @Override
    public void unregisterChatTargetListener(OnChatTargetSelectedListener listener) {
        mChatTargetListeners.remove(listener);
    }

    private class ChannelFragmentPagerAdapter extends FragmentPagerAdapter {

        public ChannelFragmentPagerAdapter(FragmentManager fm) {
            super(fm);
        }

        @Override
        public Fragment getItem(int i) {
            Fragment fragment = null;
            Bundle args = new Bundle();
            switch (i) {
                case 0:
                    // Phase 4: carousel replaces the tree view on the phone
                    // layout. One channel per page, knob-driven — Phone/Call-*
                    // sub-channels are reached via the incoming-call overlay
                    // (Phase 5), not from here.
                    fragment = new ChannelCarouselFragment();
                    break;
                case 1:
                    fragment = new ChannelChatFragment();
                    break;
            }
            fragment.setArguments(args);
            return fragment;
        }

        @Override
        public CharSequence getPageTitle(int position) {
            switch (position) {
                case 0:
                    return getString(R.string.channel).toUpperCase();
                case 1:
                    return getString(R.string.chat).toUpperCase();
                default:
                    return null;
            }
        }

        @Override
        public int getCount() {
            return 2;
        }
    }
}
