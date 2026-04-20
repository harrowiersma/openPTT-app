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

    public void submit(List<? extends IUser> users) {
        mUsers.clear();
        if (users != null) mUsers.addAll(users);
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
                .inflate(R.layout.overlay_user_row, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        IUser u = mUsers.get(position);
        h.name.setText(u.getName());
        if (u.isSelfDeafened()) {
            h.state.setImageResource(R.drawable.outline_circle_deafened);
        } else if (u.isSelfMuted()) {
            h.state.setImageResource(R.drawable.outline_circle_muted);
        } else if (u.isDeafened()) {
            h.state.setImageResource(R.drawable.outline_circle_server_deafened);
        } else if (u.isMuted()) {
            h.state.setImageResource(R.drawable.outline_circle_server_muted);
        } else if (u.isSuppressed()) {
            h.state.setImageResource(R.drawable.outline_circle_suppressed);
        } else if (u.getTalkState() == TalkState.TALKING) {
            h.state.setImageResource(R.drawable.outline_circle_talking_on);
        } else {
            h.state.setImageResource(R.drawable.outline_circle_talking_off);
        }
    }

    @Override
    public int getItemCount() {
        return mUsers.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView state;
        final TextView name;

        VH(@NonNull View v) {
            super(v);
            state = v.findViewById(R.id.user_row_state);
            name = v.findViewById(R.id.user_row_name);
        }
    }
}
