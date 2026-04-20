/*
 * Replaces the hierarchical channel tree with a one-channel-per-page
 * ViewPager2 carousel. Only direct children of Root are surfaced —
 * Phone/Call-* sub-channels are reached via the incoming-call overlay
 * (Phase 5), not the knob.
 *
 * Navigation is knob-driven (F5/F6 → MumlaService.switchChannel(...) →
 * IHumlaSession.joinChannel(...)). The carousel watches
 * HumlaObserver.onUserJoinedChannel for the session user and programmatically
 * scrolls its ViewPager2 to match. User swipes are disabled on the inner
 * pager so the outer CHANNEL↔CHAT ViewPager owns horizontal gestures.
 */

package se.lublin.mumla.channel;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
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
    private CarouselAdapter mAdapter;
    /** Snapshot of channel IDs currently shown, left-to-right. */
    private final List<Integer> mChannelIds = new ArrayList<>();

    private final IHumlaObserver mObserver = new HumlaObserver() {
        @Override public void onUserJoinedChannel(IUser user, IChannel newChannel, IChannel oldChannel) {
            // Snap to the session user's new channel. For other users'
            // moves the child ChannelCardFragments update their own
            // user lists via their own observers.
            if (getService() == null || !getService().isConnected()) return;
            try {
                if (user != null
                        && user.getSession() == getService().HumlaSession().getSessionId()
                        && newChannel != null) {
                    scrollToChannelId(newChannel.getId(), true);
                }
            } catch (IllegalStateException e) {
                Log.d(TAG, "onUserJoinedChannel: " + e);
            }
        }
        @Override public void onChannelAdded(IChannel channel) { rebuild(); }
        @Override public void onChannelRemoved(IChannel channel) { rebuild(); }
        @Override public void onChannelStateUpdated(IChannel channel) { rebuild(); }
        @Override public void onDisconnected(HumlaException e) {
            mChannelIds.clear();
            if (mAdapter != null) mAdapter.notifyDataSetChanged();
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_channel_carousel, container, false);
        mPager = v.findViewById(R.id.channelCarouselPager);
        // Outer CHANNEL↔CHAT ViewPager owns horizontal swipes; the
        // carousel moves only via the knob (F5/F6 → switchChannel).
        mPager.setUserInputEnabled(false);
        mAdapter = new CarouselAdapter(this);
        mPager.setAdapter(mAdapter);
        return v;
    }

    @Override
    public void onServiceBound(IHumlaService service) {
        super.onServiceBound(service);
        rebuild();
    }

    @Override
    public IHumlaObserver getServiceObserver() {
        return mObserver;
    }

    /**
     * Re-snapshot root's direct children (filtering Phone/Call-*), and
     * refresh the adapter. Preserves the current channel selection by
     * scrolling back to the session user's channel afterwards.
     */
    private void rebuild() {
        if (getService() == null || !getService().isConnected() || mAdapter == null) return;
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
            mAdapter.notifyDataSetChanged();

            IChannel cur = session.getSessionChannel();
            if (cur != null) scrollToChannelId(cur.getId(), false);
        } catch (IllegalStateException e) {
            Log.d(TAG, "rebuild failed: " + e);
        }
    }

    /**
     * Defensive filter for Phone/Call-* sub-channels. They should never
     * be direct children of Root (they're parented to Phone), but if the
     * Murmur tree is ever reorganized a stray Call-N at root level must
     * still be skipped — it's reached only via the incoming-call overlay.
     */
    private static boolean shouldSurface(IChannel c) {
        if (c == null) return false;
        String name = c.getName() == null ? "" : c.getName();
        return !name.startsWith("Call-");
    }

    private void scrollToChannelId(int channelId, boolean smooth) {
        if (mPager == null) return;
        int idx = mChannelIds.indexOf(channelId);
        if (idx < 0) return;
        if (mPager.getCurrentItem() != idx) {
            mPager.setCurrentItem(idx, smooth);
        }
    }

    private class CarouselAdapter extends FragmentStateAdapter {
        CarouselAdapter(Fragment host) { super(host); }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            return ChannelCardFragment.newInstance(mChannelIds.get(position));
        }

        @Override
        public int getItemCount() {
            return mChannelIds.size();
        }

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
