package com.anzakchat.app.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.anzakchat.app.R;
import com.anzakchat.app.model.RoomModel;
import com.anzakchat.app.util.UiUtils;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Odalar listesi iki bölüme ayrılır:
 *  1) "Odalarım" — zaten üye olduğumuz / konuştuğumuz odalar (alfabetik)
 *  2) "Diğer Odalar" — henüz katılmadığımız odalar (alfabetik)
 * Her iki bölüm de kendi içinde Türkçe alfabetik sıraya göre dizilir.
 */
public class RoomAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ROOM = 1;

    public interface OnRoomClick {
        void onClick(RoomModel room);
    }

    private static final class Row {
        final boolean isHeader;
        final String headerTitle;
        final RoomModel room;

        Row(String headerTitle) {
            this.isHeader = true;
            this.headerTitle = headerTitle;
            this.room = null;
        }

        Row(RoomModel room) {
            this.isHeader = false;
            this.headerTitle = null;
            this.room = room;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final OnRoomClick listener;
    private final String myUid;
    private final Collator collator;

    public RoomAdapter(String myUid, OnRoomClick listener) {
        this.myUid = myUid;
        this.listener = listener;
        this.collator = Collator.getInstance(new Locale("tr", "TR"));
        this.collator.setStrength(Collator.PRIMARY);
    }

    public void submit(List<RoomModel> newRooms) {
        List<RoomModel> mine = new ArrayList<>();
        List<RoomModel> others = new ArrayList<>();
        for (RoomModel r : newRooms) {
            if (r.isMember()) {
                mine.add(r);
            } else {
                others.add(r);
            }
        }
        Comparator<RoomModel> byName = (a, b) -> {
            String na = a.getName() != null ? a.getName() : "";
            String nb = b.getName() != null ? b.getName() : "";
            return collator.compare(na, nb);
        };
        mine.sort(byName);
        others.sort(byName);

        rows.clear();
        if (!mine.isEmpty()) {
            rows.add(new Row("Odalarım"));
            for (RoomModel r : mine) rows.add(new Row(r));
        }
        if (!others.isEmpty()) {
            rows.add(new Row("Diğer Odalar"));
            for (RoomModel r : others) rows.add(new Row(r));
        }
        notifyDataSetChanged();
    }

    /** Kart sayısı (başlıklar hariç) — boş durum kontrolü için kullanılır. */
    public int getRoomCount() {
        int count = 0;
        for (Row row : rows) if (!row.isHeader) count++;
        return count;
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).isHeader ? TYPE_HEADER : TYPE_ROOM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_section_header, parent, false);
            return new HeaderVH(v);
        }
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_room, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        if (holder instanceof HeaderVH) {
            ((HeaderVH) holder).title.setText(row.headerTitle);
            return;
        }
        RoomModel room = row.room;
        VH vh = (VH) holder;
        vh.name.setText(room.getName());
        int memberCount = room.getMemberCount();

        String lastText = room.getLastMessageText();
        if (lastText != null) {
            boolean isMine = myUid != null && myUid.equals(room.getLastMessageSenderUid());
            String sender = isMine ? "Sen" : room.getLastMessageSender();
            vh.meta.setText(sender + ": " + UiUtils.truncate(lastText, 42));
        } else {
            vh.meta.setText(memberCount + " üye");
        }
        vh.lock.setVisibility(room.isPrivate() ? View.VISIBLE : View.GONE);
        vh.avatar.setText(room.getName() != null && !room.getName().isEmpty()
                ? room.getName().substring(0, 1).toUpperCase() : "?");
        vh.itemView.setOnClickListener(v -> listener.onClick(room));
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView name, meta, avatar;
        ImageView lock;

        VH(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.room_name);
            meta = itemView.findViewById(R.id.room_meta);
            avatar = itemView.findViewById(R.id.room_avatar);
            lock = itemView.findViewById(R.id.room_lock_icon);
        }
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        TextView title;

        HeaderVH(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.section_header_title);
        }
    }
}
