package com.anzakchat.app.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.anzakchat.app.R;
import com.anzakchat.app.model.DmSummary;

import java.util.List;

/**
 * "Çevrimiçi Kullanıcılar" dialogunda gösterilen basit liste. item_dm.xml'i
 * yeniden kullanır (aynı kart görünümü), sadece durum metni her zaman
 * "Çevrimiçi" ve yeşil nokta görünür olacak şekilde bağlanır. Tıklanınca
 * DM açma callback'i tetiklenir.
 */
public class OnlineUsersAdapter extends RecyclerView.Adapter<OnlineUsersAdapter.VH> {

    public interface OnUserClickListener {
        void onUserClick(DmSummary user);
    }

    private final List<DmSummary> users;
    private final OnUserClickListener listener;

    public OnlineUsersAdapter(List<DmSummary> users, OnUserClickListener listener) {
        this.users = users;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_dm, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        DmSummary u = users.get(position);
        holder.username.setText(u.otherUsername);
        holder.status.setText("Çevrimiçi");
        holder.avatar.setText(u.otherUsername != null && !u.otherUsername.isEmpty()
                ? u.otherUsername.substring(0, 1).toUpperCase() : "?");
        holder.onlineDot.setVisibility(View.VISIBLE);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onUserClick(u);
        });
    }

    @Override
    public int getItemCount() {
        return users.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView username, status, avatar;
        View onlineDot;
        VH(View itemView) {
            super(itemView);
            username = itemView.findViewById(R.id.dm_username);
            status = itemView.findViewById(R.id.dm_status);
            avatar = itemView.findViewById(R.id.dm_avatar);
            onlineDot = itemView.findViewById(R.id.dm_online_dot);
        }
    }
}
