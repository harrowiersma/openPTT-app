/*
 * Replaces the hierarchical channel tree with a compact tile strip +
 * current-channel user list:
 *
 *   ┌──────────────────────────┐
 *   │         CHANNELS         │   short header band
 *   ├──────────────────────────┤
 *   │         [ tile ]         │   ViewPager2 — one channel per page
 *   │          ● ● ●           │   page dots
 *   ├──────────────────────────┤
 *   │  CURRENT USERS (n)       │   "current" band
 *   ├──────────────────────────┤
 *   │  harro                   │   user list (absorbs remaining space
 *   │  yuliia                  │   — shorter on smaller screens)
 *   └──────────────────────────┘
 *
 * Only direct children of Root are surfaced — Phone/Call-*
 * sub-channels are reached via the incoming-call overlay (Phase 5),
 * not the knob. Navigation is knob-driven: F5/F6 and D-pad LEFT/RIGHT
 * both route through MumlaService.switchChannel(...), which calls
 * IHumlaSession.joinChannel(...). The carousel watches
 * HumlaObserver.onUserJoinedChannel for the session user and
 * programmatically scrolls to match; user swipes are disabled on the
 * inner pager so the outer CHANNEL↔CHAT host owns horizontal gestures.
 */

package se.lublin.mumla.channel;

import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import se.lublin.humla.IHumlaService;
import se.lublin.humla.IHumlaSession;
import se.lublin.humla.model.IChannel;
import se.lublin.humla.model.IUser;
import se.lublin.humla.util.HumlaException;
import se.lublin.humla.util.HumlaObserver;
import se.lublin.humla.util.IHumlaObserver;
import se.lublin.mumla.R;
import se.lublin.mumla.util.HumlaServiceFragment;

public class ChannelCarouselFragment extends HumlaServiceFragment {
    private static final String TAG = ChannelCarouselFragment.class.getName();

    private ViewPager2 mPager;
    private LinearLayout mDots;
    private TextView mCurrentUsersBand;
    private RecyclerView mCurrentUsersList;
    private UserRowAdapter mUsersAdapter;
    private CarouselAdapter mPagerAdapter;
    /** Destination-channel pills floating on either side of the
     *  ViewPager2. Updated on every channel-tree change + page change
     *  so the operator sees where the D-pad/knob will take them. */
    private TextView mSoftkeyLeft;
    private TextView mSoftkeyRight;
    /** Snapshot of channel IDs currently shown in the carousel, L→R. */
    private final List<Integer> mChannelIds = new ArrayList<>();

    private final IHumlaObserver mObserver = new HumlaObserver() {
        @Override public void onUserJoinedChannel(IUser user, IChannel newChannel, IChannel oldChannel) {
            if (getService() == null || !getService().isConnected()) return;
            try {
                int selfSession = getService().HumlaSession().getSessionId();
                if (user != null && user.getSession() == selfSession && newChannel != null) {
                    scrollToChannelId(newChannel.getId(), true);
                    updateCurrentUsers(newChannel);
                    return;
                }
                // Somebody else moved — if their old or new channel is the
                // one we're currently displaying users for, refresh it.
                IChannel selected = currentChannel();
                if (selected != null &&
                        (newChannel != null && newChannel.getId() == selected.getId()
                                || oldChannel != null && oldChannel.getId() == selected.getId())) {
                    updateCurrentUsers(selected);
                }
            } catch (IllegalStateException e) {
                Log.d(TAG, "onUserJoinedChannel: " + e);
            }
        }
        @Override public void onUserConnected(IUser user) { refreshCurrent(); }
        @Override public void onUserRemoved(IUser user, String reason) { refreshCurrent(); }
        @Override public void onUserStateUpdated(IUser user) {
            if (mUsersAdapter != null) mUsersAdapter.refreshUser(user);
        }
        @Override public void onUserTalkStateUpdated(IUser user) {
            if (mUsersAdapter != null) mUsersAdapter.refreshUser(user);
        }
        @Override public void onChannelAdded(IChannel channel) { rebuild(); }
        @Override public void onChannelRemoved(IChannel channel) { rebuild(); }
        @Override public void onChannelStateUpdated(IChannel channel) { rebuild(); }
        @Override public void onDisconnected(HumlaException e) {
            mChannelIds.clear();
            if (mPagerAdapter != null) mPagerAdapter.notifyDataSetChanged();
            if (mUsersAdapter != null) mUsersAdapter.submit(null);
            if (mCurrentUsersBand != null) mCurrentUsersBand.setText("");
            if (mSoftkeyLeft != null) mSoftkeyLeft.setText("◀");
            if (mSoftkeyRight != null) mSoftkeyRight.setText("▶");
            renderDots(0, -1);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_channel_carousel, container, false);

        mPager = v.findViewById(R.id.channelCarouselPager);
        mPager.setUserInputEnabled(false);  // knob-driven only, no swipe
        mPagerAdapter = new CarouselAdapter(this);
        mPager.setAdapter(mPagerAdapter);
        // Peek-view: show a sliver of the previous and next channel
        // tiles on either side of the current one so the operator knows
        // there's more without having to press a key to find out. The
        // inner RecyclerView owns padding + clip behavior on ViewPager2.
        if (mPager.getChildAt(0) instanceof RecyclerView) {
            RecyclerView inner = (RecyclerView) mPager.getChildAt(0);
            int peek = dp(36);
            inner.setPadding(peek, 0, peek, 0);
            inner.setClipToPadding(false);
        }
        mPager.setOffscreenPageLimit(1);
        mPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                renderDots(mChannelIds.size(), position);
                IChannel c = channelAt(position);
                if (c != null) updateCurrentUsers(c);
                updateSoftkeyLabels(position);
            }
        });

        mSoftkeyLeft = v.findViewById(R.id.softkeyLeft);
        mSoftkeyRight = v.findViewById(R.id.softkeyRight);
        mDots = v.findViewById(R.id.channelCarouselDots);
        mCurrentUsersBand = v.findViewById(R.id.channelCurrentUsersBand);
        mCurrentUsersList = v.findViewById(R.id.channelCurrentUsersList);
        mCurrentUsersList.setLayoutManager(new LinearLayoutManager(requireContext()));
        mUsersAdapter = new UserRowAdapter();
        mCurrentUsersList.setAdapter(mUsersAdapter);

        // Task 10: paint the status pill with whatever the service has
        // cached right now. If the service isn't bound yet or hasn't
        // confirmed a status this session the pill stays hidden until
        // a later refresh (from cycleStatus, onResume hydration, or
        // MumlaService.onConnected) calls back in.
        refreshStatusPill();

        return v;
    }

    /**
     * Task 10: repaint the carousel's compact presence pill from the
     * service's cached status/audibility. Safe to call from any path
     * (post-cycle, onResume hydration, connect hydration) — null-safe
     * when the fragment view isn't attached or the service isn't bound.
     *
     * Null label → hidden pill (no presence known yet this session).
     * Online/busy/offline → colored dot + matching label text.
     * Audible == FALSE → red volume-off icon appears to the right.
     */
    public void refreshStatusPill() {
        View root = getView();
        if (root == null || getService() == null) return;

        final String label = getService().getCurrentStatus();
        final Boolean audible = getService().getCurrentAudible();

        View pill = root.findViewById(R.id.status_pill);
        View dot = root.findViewById(R.id.status_dot);
        TextView tv = root.findViewById(R.id.status_label);
        ImageView mutedIcon = root.findViewById(R.id.status_muted_icon);
        if (pill == null || dot == null || tv == null) return;

        if (label == null) {
            pill.setVisibility(View.GONE);
            return;
        }
        pill.setVisibility(View.VISIBLE);
        switch (label) {
            case "online":
                dot.setBackgroundResource(R.drawable.circle_success);
                tv.setText("Online");
                break;
            case "busy":
                dot.setBackgroundResource(R.drawable.circle_amber);
                tv.setText("Busy");
                break;
            case "offline":
            default:
                dot.setBackgroundResource(R.drawable.circle_muted);
                tv.setText("Offline");
                break;
        }
        if (mutedIcon != null) {
            mutedIcon.setVisibility(Boolean.FALSE.equals(audible)
                    ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onServiceBound(IHumlaService service) {
        super.onServiceBound(service);
        rebuild();
        // Service just bound — whatever presence/audibility the service
        // has cached (from earlier this session or a hydration POST)
        // should paint the pill immediately instead of waiting for the
        // next orange-button cycle.
        refreshStatusPill();
    }

    @Override
    public IHumlaObserver getServiceObserver() {
        return mObserver;
    }

    /**
     * Re-snapshot root's direct children (filtering Phone/Call-*), refresh
     * the pager adapter, restore the current-session-channel as the
     * selected tile, and repopulate the users list.
     */
    private void rebuild() {
        if (getService() == null || !getService().isConnected() || mPagerAdapter == null) return;
        try {
            IHumlaSession session = getService().HumlaSession();
            IChannel root = session.getRootChannel();
            if (root == null) return;
            List<? extends IChannel> children = root.getSubchannels();
            if (children == null) children = Collections.emptyList();

            List<IChannel> filtered = new ArrayList<>();
            for (IChannel c : children) {
                if (shouldSurface(c)) filtered.add(c);
            }
            Collections.sort(filtered, new Comparator<IChannel>() {
                @Override public int compare(IChannel a, IChannel b) {
                    String an = a.getName() == null ? "" : a.getName();
                    String bn = b.getName() == null ? "" : b.getName();
                    return an.compareToIgnoreCase(bn);
                }
            });

            mChannelIds.clear();
            for (IChannel c : filtered) mChannelIds.add(c.getId());
            mPagerAdapter.notifyDataSetChanged();

            IChannel cur = session.getSessionChannel();
            int curIdx = cur == null ? -1 : mChannelIds.indexOf(cur.getId());
            if (curIdx >= 0) {
                if (mPager.getCurrentItem() != curIdx) mPager.setCurrentItem(curIdx, false);
                updateCurrentUsers(cur);
            } else if (!mChannelIds.isEmpty()) {
                IChannel first = channelAt(0);
                if (first != null) updateCurrentUsers(first);
            }
            renderDots(mChannelIds.size(), mPager.getCurrentItem());
            updateSoftkeyLabels(mPager.getCurrentItem());
        } catch (IllegalStateException e) {
            Log.d(TAG, "rebuild failed: " + e);
        }
    }

    /**
     * Refresh the floating pill labels for the given current-tile
     * position. Shows the previous/next destination channel names
     * ("◀ Emergency" / "Phone ▶") so the operator knows where the
     * D-pad LEFT/RIGHT (and rotary knob) will take them.
     *
     * Both pills are sized to the widest possible label in the set
     * ("◀ <longest name>" + "<longest name> ▶") so they stay visually
     * balanced instead of jittering around the edges as the user
     * scrolls through channels of varying name length.
     */
    private void updateSoftkeyLabels(int currentPos) {
        if (mSoftkeyLeft == null || mSoftkeyRight == null) return;
        String prevName = "";
        String nextName = "";
        if (currentPos >= 0 && mChannelIds.size() > 1) {
            int prevIdx = (currentPos - 1 + mChannelIds.size()) % mChannelIds.size();
            int nextIdx = (currentPos + 1) % mChannelIds.size();
            IChannel prev = channelAt(prevIdx);
            IChannel next = channelAt(nextIdx);
            if (prev != null && prev.getName() != null) prevName = prev.getName();
            if (next != null && next.getName() != null) nextName = next.getName();
        }
        mSoftkeyLeft.setText(prevName.isEmpty() ? "◀" : "◀ " + prevName);
        mSoftkeyRight.setText(nextName.isEmpty() ? "▶" : nextName + " ▶");
        applySoftkeyFixedWidth();
    }

    /**
     * Measure the widest possible pill label across all channels in the
     * carousel and apply it as minWidth to both pills — guarantees
     * both ends of the bar are the same size and the text is centered.
     */
    private void applySoftkeyFixedWidth() {
        if (mSoftkeyLeft == null || mSoftkeyRight == null) return;
        String longest = "";
        if (getService() != null && getService().isConnected()) {
            try {
                IHumlaSession session = getService().HumlaSession();
                for (Integer id : mChannelIds) {
                    IChannel c = findChannelById(session.getRootChannel(), id);
                    if (c == null || c.getName() == null) continue;
                    if (c.getName().length() > longest.length()) longest = c.getName();
                }
            } catch (IllegalStateException e) {
                // fall through with whatever we have
            }
        }
        // Measure "◀ <longest>" (or "<longest> ▶", same length) using
        // the left pill's paint so the font/size/bold match the real
        // render. Add pill internal padding to arrive at the outer
        // minWidth value.
        String sample = "◀ " + longest;
        float textW = mSoftkeyLeft.getPaint().measureText(sample);
        int padding = mSoftkeyLeft.getPaddingStart() + mSoftkeyLeft.getPaddingEnd();
        int minW = Math.round(textW) + padding + dp(2);  // small slack
        mSoftkeyLeft.setMinWidth(minW);
        mSoftkeyRight.setMinWidth(minW);
    }

    /** Repopulate the user list + band for the currently-selected tile. */
    private void refreshCurrent() {
        IChannel c = currentChannel();
        if (c != null) updateCurrentUsers(c);
    }

    private void updateCurrentUsers(@Nullable IChannel channel) {
        if (channel == null) return;
        List<? extends IUser> users = channel.getUsers();
        int n = BotUsers.countHumans(users);
        if (mUsersAdapter != null) mUsersAdapter.submit(users);
        if (mCurrentUsersBand != null) {
            String name = channel.getName() == null ? "" : channel.getName();
            mCurrentUsersBand.setText(getString(R.string.channel_carousel_current_users_band,
                    name.toUpperCase(), n));
        }
    }

    /** Delegates to {@link HumanChannels#isVisible} so knob navigation and
     *  the carousel render the exact same set. */
    private static boolean shouldSurface(IChannel c) {
        return HumanChannels.isVisible(c);
    }

    private void scrollToChannelId(int channelId, boolean smooth) {
        if (mPager == null) return;
        int idx = mChannelIds.indexOf(channelId);
        if (idx < 0) return;
        if (mPager.getCurrentItem() != idx) {
            mPager.setCurrentItem(idx, smooth);
        }
    }

    @Nullable
    private IChannel currentChannel() {
        return channelAt(mPager == null ? -1 : mPager.getCurrentItem());
    }

    @Nullable
    private IChannel channelAt(int position) {
        if (position < 0 || position >= mChannelIds.size()) return null;
        if (getService() == null || !getService().isConnected()) return null;
        try {
            IHumlaSession session = getService().HumlaSession();
            if (session == null) return null;
            return findChannelById(session.getRootChannel(), mChannelIds.get(position));
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private static IChannel findChannelById(IChannel node, int id) {
        if (node == null) return null;
        if (node.getId() == id) return node;
        List<? extends IChannel> subs = node.getSubchannels();
        if (subs == null) return null;
        for (IChannel c : subs) {
            IChannel hit = findChannelById(c, id);
            if (hit != null) return hit;
        }
        return null;
    }

    /** Render the page-indicator dots for the given count + selected index. */
    private void renderDots(int count, int selected) {
        if (mDots == null) return;
        mDots.removeAllViews();
        int dotSize = dp(5);
        int margin = dp(3);
        for (int i = 0; i < count; i++) {
            TextView dot = new TextView(requireContext());
            dot.setText("\u2022"); // bullet
            dot.setGravity(Gravity.CENTER);
            dot.setTextSize(10f);
            dot.setTextColor(i == selected ? 0xFFFFFFFF : 0xFF555555);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = margin;
            lp.rightMargin = margin;
            dot.setLayoutParams(lp);
            mDots.addView(dot);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private class CarouselAdapter extends FragmentStateAdapter {
        CarouselAdapter(Fragment host) { super(host); }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            return ChannelCardFragment.newInstance(mChannelIds.get(position));
        }

        @Override public int getItemCount() { return mChannelIds.size(); }

        @Override
        public long getItemId(int position) {
            return mChannelIds.get(position);
        }

        @Override
        public boolean containsItem(long itemId) {
            for (Integer id : mChannelIds) {
                if (id.longValue() == itemId) return true;
            }
            return false;
        }
    }
}
