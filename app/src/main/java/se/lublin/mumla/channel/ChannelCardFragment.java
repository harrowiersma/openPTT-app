/*
 * A single page of the channel carousel: one channel's name + member
 * count + user list. Bound to a channel by ID via Fragment arguments;
 * the fragment looks up the IChannel on the server when the service
 * binder becomes available.
 */

package se.lublin.mumla.channel;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import se.lublin.humla.IHumlaService;
import se.lublin.humla.IHumlaSession;
import se.lublin.humla.model.IChannel;
import se.lublin.humla.model.IUser;
import se.lublin.humla.util.HumlaException;
import se.lublin.humla.util.HumlaObserver;
import se.lublin.humla.util.IHumlaObserver;
import se.lublin.mumla.R;
import se.lublin.mumla.util.HumlaServiceFragment;

public class ChannelCardFragment extends HumlaServiceFragment {
    private static final String TAG = ChannelCardFragment.class.getName();
    private static final String ARG_CHANNEL_ID = "channel_id";

    private int mChannelId = -1;
    private TextView mName;
    private TextView mMembers;
    private RecyclerView mUsers;
    private UserRowAdapter mAdapter;

    private final IHumlaObserver mObserver = new HumlaObserver() {
        @Override public void onUserJoinedChannel(IUser user, IChannel newChannel, IChannel oldChannel) {
            rebind();
        }
        @Override public void onUserConnected(IUser user) { rebind(); }
        @Override public void onUserRemoved(IUser user, String reason) { rebind(); }
        @Override public void onUserStateUpdated(IUser user) {
            if (mAdapter != null) mAdapter.refreshUser(user);
        }
        @Override public void onUserTalkStateUpdated(IUser user) {
            if (mAdapter != null) mAdapter.refreshUser(user);
        }
        @Override public void onChannelStateUpdated(IChannel channel) {
            if (channel != null && channel.getId() == mChannelId) rebind();
        }
        @Override public void onDisconnected(HumlaException e) {
            if (mAdapter != null) mAdapter.submit(null);
        }
    };

    public static ChannelCardFragment newInstance(int channelId) {
        ChannelCardFragment f = new ChannelCardFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_CHANNEL_ID, channelId);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        mChannelId = args != null ? args.getInt(ARG_CHANNEL_ID, -1) : -1;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_channel_card, container, false);
        mName = v.findViewById(R.id.channelCardName);
        mMembers = v.findViewById(R.id.channelCardMembers);
        mUsers = v.findViewById(R.id.channelCardUserList);
        mUsers.setLayoutManager(new LinearLayoutManager(requireContext()));
        mAdapter = new UserRowAdapter();
        mUsers.setAdapter(mAdapter);
        return v;
    }

    @Override
    public void onServiceBound(IHumlaService service) {
        super.onServiceBound(service);
        rebind();
    }

    @Override
    public IHumlaObserver getServiceObserver() {
        return mObserver;
    }

    /** Re-read the channel from the server binding and repopulate the view. */
    private void rebind() {
        if (getService() == null || !getService().isConnected()) return;
        try {
            IHumlaSession session = getService().HumlaSession();
            if (session == null) return;
            IChannel channel = findChannelById(session.getRootChannel(), mChannelId);
            if (channel == null) {
                if (mName != null) mName.setText("");
                if (mMembers != null) mMembers.setText("");
                if (mAdapter != null) mAdapter.submit(null);
                return;
            }
            if (mName != null) mName.setText(channel.getName());
            if (mAdapter != null) mAdapter.submit(channel.getUsers());
            if (mMembers != null) {
                int n = channel.getUsers() == null ? 0 : channel.getUsers().size();
                mMembers.setText(getResources().getQuantityString(
                        R.plurals.channel_card_member_count, n, n));
            }
        } catch (IllegalStateException e) {
            Log.d(TAG, "rebind failed: " + e);
        }
    }

    private static IChannel findChannelById(IChannel node, int id) {
        if (node == null) return null;
        if (node.getId() == id) return node;
        java.util.List<? extends IChannel> subs = node.getSubchannels();
        if (subs == null) return null;
        for (IChannel c : subs) {
            IChannel hit = findChannelById(c, id);
            if (hit != null) return hit;
        }
        return null;
    }
}
