package com.anzakchat.app.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.anzakchat.app.R;

import java.util.List;

/**
 * "Üyeleri Yönet" dialogunda gösterilen üye listesi — avatar, çevrimiçi noktası
 * ve oda kurucusu rozeti içerir. Bir satıra dokununca kick/ban action-sheet'i
 * (ChatActivity#showMemberAction) tetiklenir.
 */
public class MemberAdapter extends RecyclerView.Adapter<MemberAdapter.VH> {

    public static class MemberRow {
        public final String uid;
        public final String username;
        public final boolean online;
        public final boolean owner;

        public MemberRow(String uid, String username, boolean online, boolean owner) {
            this.uid = uid;
            this.username = username;
            this.online = online;
            this.owner = owner;
        }
    }

    public interface OnMemberClickListener {
        void onMemberClick(MemberRow member);
    }

    private final List<MemberRow> members;
    private final OnMemberClickListener listener;

    public MemberAdapter(List<MemberRow> members, OnMemberClickListener listener) {
        this.members = members;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_member, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        MemberRow m = members.get(position);
        holder.name.setText(m.username);
        holder.avatar.setText(m.username != null && !m.username.isEmpty()
                ? m.username.substring(0, 1).toUpperCase() : "?");
        holder.status.setText(m.online ? "Çevrimiçi" : "Çevrimdışı");
        holder.onlineDot.setVisibility(m.online ? View.VISIBLE : View.GONE);
        holder.ownerBadge.setVisibility(m.owner ? View.VISIBLE : View.GONE);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onMemberClick(m);
        });
    }

    @Override
    public int getItemCount() {
        return members.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView avatar, name, status, ownerBadge;
        View onlineDot;
        VH(View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.member_avatar);
            name = itemView.findViewById(R.id.member_name);
            status = itemView.findViewById(R.id.member_status);
            onlineDot = itemView.findViewById(R.id.member_online_dot);
            ownerBadge = itemView.findViewById(R.id.member_owner_badge);
        }
    }
}
