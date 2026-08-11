package com.anzakchat.app.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.anzakchat.app.R;

import java.util.List;

/** Sık kullanılan emoji ızgarası — örnek web projesindeki emoji-picker'ın Android karşılığı. */
public class EmojiAdapter extends RecyclerView.Adapter<EmojiAdapter.VH> {

    public interface OnEmojiClickListener {
        void onEmojiClick(String emoji);
    }

    private final List<String> emojis;
    private final OnEmojiClickListener listener;

    public EmojiAdapter(List<String> emojis, OnEmojiClickListener listener) {
        this.emojis = emojis;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_emoji, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        String emoji = emojis.get(position);
        holder.text.setText(emoji);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onEmojiClick(emoji);
        });
    }

    @Override
    public int getItemCount() {
        return emojis.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView text;
        VH(View itemView) {
            super(itemView);
            text = (TextView) itemView;
        }
    }
}
