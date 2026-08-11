package com.anzakchat.app.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.anzakchat.app.R;
import com.anzakchat.app.model.DmSummary;
import com.anzakchat.app.util.UiUtils;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Mesajlar listesi iki bölüme ayrılır:
 *  1) "Sohbetlerim" — daha önce konuştuğumuz kullanıcılar (alfabetik)
 *  2) "Diğer Kullanıcılar" — henüz konuşmadığımız diğer kullanıcılar (alfabetik)
 * Her iki bölüm de kendi içinde Türkçe alfabetik sıraya göre dizilir.
 */
public class DmAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_DM = 1;

    public interface OnDmClick {
        void onClick(DmSummary dm);
    }

    private static final class Row {
        final boolean isHeader;
        final String headerTitle;
        final DmSummary dm;

        Row(String headerTitle) {
            this.isHeader = true;
            this.headerTitle = headerTitle;
            this.dm = null;
        }

        Row(DmSummary dm) {
            this.isHeader = false;
            this.headerTitle = null;
            this.dm = dm;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final OnDmClick listener;
    private final Collator collator;

    public DmAdapter(OnDmClick listener) {
        this.listener = listener;
        this.collator = Collator.getInstance(new Locale("tr", "TR"));
        this.collator.setStrength(Collator.PRIMARY);
    }

    /**
     * @param conversations daha önce konuştuğumuz kullanıcılar (üst bölüm)
     * @param others        henüz konuşmadığımız diğer kullanıcılar (alt bölüm)
     */
    public void submit(List<DmSummary> conversations, List<DmSummary> others) {
        Comparator<DmSummary> byName = (a, b) -> {
            String na = a.otherUsername != null ? a.otherUsername : "";
            String nb = b.otherUsername != null ? b.otherUsername : "";
            return collator.compare(na, nb);
        };
        List<DmSummary> convosSorted = new ArrayList<>(conversations);
        List<DmSummary> othersSorted = new ArrayList<>(others);
        convosSorted.sort(byName);
        othersSorted.sort(byName);

        rows.clear();
        if (!convosSorted.isEmpty()) {
            rows.add(new Row("Sohbetlerim"));
            for (DmSummary d : convosSorted) rows.add(new Row(d));
        }
        if (!othersSorted.isEmpty()) {
            rows.add(new Row("Diğer Kullanıcılar"));
            for (DmSummary d : othersSorted) rows.add(new Row(d));
        }
        notifyDataSetChanged();
    }

    /** Kart sayısı (başlıklar hariç) — boş durum kontrolü için kullanılır. */
    public int getDmCount() {
        int count = 0;
        for (Row row : rows) if (!row.isHeader) count++;
        return count;
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).isHeader ? TYPE_HEADER : TYPE_DM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_section_header, parent, false);
            return new HeaderVH(v);
        }
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_dm, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        if (holder instanceof HeaderVH) {
            ((HeaderVH) holder).title.setText(row.headerTitle);
            return;
        }
        DmSummary dm = row.dm;
        VH vh = (VH) holder;
        vh.username.setText(dm.otherUsername);

        if (dm.lastMessageText != null) {
            String sender = dm.lastMessageIsMine ? "Sen" : dm.lastMessageSender;
            vh.status.setText(sender + ": " + UiUtils.truncate(dm.lastMessageText, 42));
        } else {
            vh.status.setText(dm.online ? "Çevrimiçi" : "Çevrimdışı");
        }

        vh.avatar.setText(dm.otherUsername != null && !dm.otherUsername.isEmpty()
                ? dm.otherUsername.substring(0, 1).toUpperCase() : "?");
        UiUtils.applyAvatarPhoto(vh.itemView.getResources(), vh.avatarPhoto, vh.avatar, dm.photoBase64);
        vh.onlineDot.setVisibility(dm.online ? View.VISIBLE : View.GONE);
        vh.itemView.setOnClickListener(v -> listener.onClick(dm));
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView username, status, avatar;
        ImageView avatarPhoto;
        View onlineDot;

        VH(View itemView) {
            super(itemView);
            username = itemView.findViewById(R.id.dm_username);
            status = itemView.findViewById(R.id.dm_status);
            avatar = itemView.findViewById(R.id.dm_avatar);
            avatarPhoto = itemView.findViewById(R.id.dm_avatar_photo);
            onlineDot = itemView.findViewById(R.id.dm_online_dot);
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
