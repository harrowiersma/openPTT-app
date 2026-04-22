/*
 * RecyclerView.Adapter displaying the users in a single channel.
 * Reuses the existing overlay_user_row layout and the same talk-state /
 * mute / deafen drawable logic as ChannelAdapter, ported to a modern
 * RecyclerView for use inside the ViewPager2 channel carousel.
 */

package se.lublin.mumla.channel;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import se.lublin.humla.model.IUser;
import se.lublin.humla.model.TalkState;
import se.lublin.mumla.R;

public class UserRowAdapter extends RecyclerView.Adapter<UserRowAdapter.VH> {

    private final List<IUser> mUsers = new ArrayList<>();
    private PresenceCache mPresenceCache;
    private String mSelfName;

    /** Fragment calls this once after constructing the adapter, then
     *  re-calls it whenever the connection identity changes. Both args
     *  may be null until the service is bound. */
    public void setPresenceContext(PresenceCache cache, String selfName) {
        mPresenceCache = cache;
        mSelfName = selfName;
    }

    public void submit(List<? extends IUser> users) {
        mUsers.clear();
        if (users != null) {
            for (IUser u : users) {
                if (u == null || BotUsers.isBot(u)) continue;
                if (PresenceFilter.isHidden(u, mPresenceCache, mSelfName)) continue;
                mUsers.add(u);
            }
        }
        // Stable alphabetical order so the list doesn't jump when the
        // server returns users in a different order after a reconnect.
        Collections.sort(mUsers, (a, b) -> {
            String an = a.getName() == null ? "" : a.getName();
            String bn = b.getName() == null ? "" : b.getName();
            return an.compareToIgnoreCase(bn);
        });
        notifyDataSetChanged();
    }

    /** Find and redraw the row for a single user (talk-state flicker). */
    public void refreshUser(IUser user) {
        if (user == null) return;
        for (int i = 0; i < mUsers.size(); i++) {
            if (mUsers.get(i).getSession() == user.getSession()) {
                mUsers.set(i, user);
                notifyItemChanged(i);
                return;
            }
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.channel_card_user_row, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        IUser u = mUsers.get(position);
        h.name.setText(u.getName());

        // Pick the tightest-applicable status, matching the legacy glyph
        // priority: self-states first (user asked for it), then server-
        // applied states, then talk-state.
        String status;
        int statusColor;
        if (u.isSelfDeafened()) {
            h.state.setImageResource(R.drawable.outline_circle_deafened);
            status = "DEAF";
            statusColor = 0xFFE53935;
        } else if (u.isSelfMuted()) {
            h.state.setImageResource(R.drawable.outline_circle_muted);
            status = "MUTED";
            statusColor = 0xFFE53935;
        } else if (u.isDeafened()) {
            h.state.setImageResource(R.drawable.outline_circle_server_deafened);
            status = "SVR DEAF";
            statusColor = 0xFF0099CC;
        } else if (u.isMuted()) {
            h.state.setImageResource(R.drawable.outline_circle_server_muted);
            status = "SVR MUTED";
            statusColor = 0xFF0099CC;
        } else if (u.isSuppressed()) {
            h.state.setImageResource(R.drawable.outline_circle_suppressed);
            status = "SUPPRESSED";
            statusColor = 0xFF666666;
        } else if (u.getTalkState() == TalkState.TALKING) {
            h.state.setImageResource(R.drawable.outline_circle_talking_on);
            status = "PTT";
            statusColor = 0xFF00C853;
        } else if (mPresenceCache != null
                   && "busy".equals(mPresenceCache.getStatus(u.getName()))) {
            // Stored presence intent. Self-state and talk-state above
            // still take priority — they're more dynamic.
            h.state.setImageResource(R.drawable.outline_circle_talking_off);
            status = "BUSY";
            statusColor = 0xFFFFBF00;
        } else {
            h.state.setImageResource(R.drawable.outline_circle_talking_off);
            status = "ONLINE";
            statusColor = 0xFF4CAF50;
        }
        if (h.status != null) {
            h.status.setText(status);
            h.status.setTextColor(statusColor);
        }
    }

    @Override
    public int getItemCount() {
        return mUsers.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView state;
        final TextView name;
        final TextView status;

        VH(@NonNull View v) {
            super(v);
            state = v.findViewById(R.id.user_row_state);
            name = v.findViewById(R.id.user_row_name);
            status = v.findViewById(R.id.user_row_status);
        }
    }
}
