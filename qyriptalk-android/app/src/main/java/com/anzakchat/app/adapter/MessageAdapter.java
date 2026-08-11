package com.anzakchat.app.adapter;

import android.media.MediaPlayer;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.anzakchat.app.R;
import com.anzakchat.app.model.MessageModel;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Mesaj listesini gösterir; gün değiştiğinde otomatik bir tarih başlığı satırı
 * ekler. Yanıt (reply) önizlemesi ve sesli mesaj oynatma da burada yönetilir.
 */
public class MessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_SENT = 1;
    private static final int TYPE_RECEIVED = 2;
    private static final int TYPE_DATE_HEADER = 3;

    public interface OnMessageLongClickListener {
        void onMessageLongClick(MessageModel message);
    }

    public interface OnFileOpenListener {
        void onFileOpen(MessageModel message);
    }

    private static class DateHeader {
        final String label;
        DateHeader(String label) { this.label = label; }
    }

    private final List<Object> rows = new ArrayList<>();
    private final String myUid;
    private final OnMessageLongClickListener longClickListener;
    private final OnFileOpenListener fileOpenListener;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("d MMMM yyyy", new Locale("tr", "TR"));
    private String lastHeaderLabel = null;

    // Aynı anda tek sesli mesaj çalsın diye tek MediaPlayer paylaşılıyor.
    private MediaPlayer activePlayer;
    private String activePlayerMessageId;
    private ImageButton activePlayButton;

    public MessageAdapter(String myUid) {
        this(myUid, null, null);
    }

    public MessageAdapter(String myUid, OnMessageLongClickListener longClickListener) {
        this(myUid, longClickListener, null);
    }

    public MessageAdapter(String myUid, OnMessageLongClickListener longClickListener, OnFileOpenListener fileOpenListener) {
        this.myUid = myUid;
        this.longClickListener = longClickListener;
        this.fileOpenListener = fileOpenListener;
    }

    public void add(MessageModel message) {
        String label = dayLabelFor(message.getTimestampMillis());
        if (label != null && !label.equals(lastHeaderLabel)) {
            rows.add(new DateHeader(label));
            lastHeaderLabel = label;
            notifyItemInserted(rows.size() - 1);
        }
        rows.add(message);
        notifyItemInserted(rows.size() - 1);
    }

    public void update(MessageModel updated) {
        if (updated == null || updated.getMessageId() == null) return;
        for (int i = 0; i < rows.size(); i++) {
            Object row = rows.get(i);
            if (row instanceof MessageModel && updated.getMessageId().equals(((MessageModel) row).getMessageId())) {
                rows.set(i, updated);
                notifyItemChanged(i);
                return;
            }
        }
    }

    public void removeById(String messageId) {
        if (messageId == null) return;
        if (messageId.equals(activePlayerMessageId)) stopActivePlayer();
        for (int i = 0; i < rows.size(); i++) {
            Object row = rows.get(i);
            if (row instanceof MessageModel && messageId.equals(((MessageModel) row).getMessageId())) {
                rows.remove(i);
                notifyItemRemoved(i);
                if (i > 0 && rows.get(i - 1) instanceof DateHeader
                        && (i >= rows.size() || rows.get(i) instanceof DateHeader)) {
                    rows.remove(i - 1);
                    notifyItemRemoved(i - 1);
                    if (i - 1 == 0 || !(rows.get(i - 2) instanceof DateHeader)) {
                        recomputeLastHeaderLabel();
                    }
                }
                return;
            }
        }
    }

    /** Bir mesaja verilen id ile yanıt önizlemesi metni bulur (reply bar için). */
    public MessageModel findById(String messageId) {
        if (messageId == null) return null;
        for (Object row : rows) {
            if (row instanceof MessageModel && messageId.equals(((MessageModel) row).getMessageId())) {
                return (MessageModel) row;
            }
        }
        return null;
    }

    private void recomputeLastHeaderLabel() {
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (rows.get(i) instanceof DateHeader) {
                lastHeaderLabel = ((DateHeader) rows.get(i)).label;
                return;
            }
        }
        lastHeaderLabel = null;
    }

    public int getMessageCount() {
        return rows.size();
    }

    public void clear() {
        stopActivePlayer();
        int count = rows.size();
        rows.clear();
        lastHeaderLabel = null;
        if (count > 0) notifyItemRangeRemoved(0, count);
    }

    /** Activity kapanırken çalan sesli mesajı durdurup kaynakları serbest bırakır. */
    public void release() {
        stopActivePlayer();
    }

    private void stopActivePlayer() {
        if (activePlayer != null) {
            try { activePlayer.stop(); } catch (Exception ignored) { }
            activePlayer.release();
            activePlayer = null;
        }
        if (activePlayButton != null) {
            activePlayButton.setImageResource(R.drawable.ic_play);
        }
        activePlayerMessageId = null;
        activePlayButton = null;
    }

    @Override
    public int getItemViewType(int position) {
        Object row = rows.get(position);
        if (row instanceof DateHeader) return TYPE_DATE_HEADER;
        MessageModel m = (MessageModel) row;
        return myUid.equals(m.getSenderUid()) ? TYPE_SENT : TYPE_RECEIVED;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_DATE_HEADER) {
            View v = inflater.inflate(R.layout.item_date_header, parent, false);
            return new HeaderVH(v);
        } else if (viewType == TYPE_SENT) {
            View v = inflater.inflate(R.layout.item_message_sent, parent, false);
            return new SentVH(v);
        } else {
            View v = inflater.inflate(R.layout.item_message_received, parent, false);
            return new ReceivedVH(v);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object row = rows.get(position);

        if (holder instanceof HeaderVH) {
            ((HeaderVH) holder).label.setText(((DateHeader) row).label);
            return;
        }

        MessageModel m = (MessageModel) row;
        String time = m.getTimestampMillis() > 0 ? timeFormat.format(m.getTimestampMillis()) : "";
        String body = renderBody(m);
        boolean isImage = "image".equals(m.getType()) && m.getMediaUrl() != null;
        boolean isVoice = "voice".equals(m.getType()) && m.getMediaUrl() != null;

        View itemView = holder.itemView;
        boolean isOwn = myUid.equals(m.getSenderUid());
        itemView.setOnLongClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            if (longClickListener != null) longClickListener.onMessageLongClick(m);
            return true;
        });

        boolean isFile = "file".equals(m.getType()) && m.getFileData() != null;
        if (isFile && fileOpenListener != null) {
            itemView.setOnClickListener(v -> fileOpenListener.onFileOpen(m));
        } else {
            itemView.setOnClickListener(null);
        }

        if (holder instanceof SentVH) {
            SentVH vh = (SentVH) holder;
            bindImage(vh.image, m, isImage);
            bindVoice(vh.voiceRow, vh.voicePlayButton, vh.voiceDuration, m, isVoice);
            bindReply(vh.replyBox, vh.replySender, vh.replyText, m);
            vh.text.setText(body);
            vh.text.setVisibility(body.isEmpty() ? View.GONE : View.VISIBLE);
            vh.time.setText(time);
            boolean isRead = m.getReadBy() != null && !m.getReadBy().isEmpty();
            vh.tick.setImageResource(isRead ? R.drawable.ic_check_double : R.drawable.ic_check_single);
        } else if (holder instanceof ReceivedVH) {
            ReceivedVH vh = (ReceivedVH) holder;
            bindImage(vh.image, m, isImage);
            bindVoice(vh.voiceRow, vh.voicePlayButton, vh.voiceDuration, m, isVoice);
            bindReply(vh.replyBox, vh.replySender, vh.replyText, m);
            vh.text.setText(body);
            vh.text.setVisibility(body.isEmpty() ? View.GONE : View.VISIBLE);
            vh.time.setText(time);
            vh.sender.setText(m.getSender());
        }
    }

    private void bindReply(View box, TextView senderView, TextView textView, MessageModel m) {
        if (box == null) return;
        if (!m.hasReply()) {
            box.setVisibility(View.GONE);
            return;
        }
        box.setVisibility(View.VISIBLE);
        senderView.setText(m.getReplyToSender() != null ? m.getReplyToSender() : "Kullanıcı");
        textView.setText(m.getReplyToPreview() != null ? m.getReplyToPreview() : "");
    }

    private void bindImage(ImageView imageView, MessageModel m, boolean isImage) {
        if (imageView == null) return;
        if (!isImage) {
            imageView.setVisibility(View.GONE);
            com.bumptech.glide.Glide.with(imageView).clear(imageView);
            return;
        }
        imageView.setVisibility(View.VISIBLE);
        com.bumptech.glide.Glide.with(imageView)
                .load(m.getMediaUrl())
                .placeholder(R.drawable.ic_check_single)
                .into(imageView);
    }

    private void bindVoice(View row, ImageButton playButton, TextView durationView, MessageModel m, boolean isVoice) {
        if (row == null) return;
        if (!isVoice) {
            row.setVisibility(View.GONE);
            return;
        }
        row.setVisibility(View.VISIBLE);
        durationView.setText(formatDuration(m.getDurationMs()));

        boolean isThisPlaying = m.getId() != null && m.getId().equals(activePlayerMessageId) && activePlayer != null;
        playButton.setImageResource(isThisPlaying ? R.drawable.ic_pause : R.drawable.ic_play);

        playButton.setOnClickListener(v -> {
            if (isThisPlaying) {
                stopActivePlayer();
                return;
            }
            stopActivePlayer();
            try {
                MediaPlayer player = new MediaPlayer();
                player.setDataSource(m.getMediaUrl());
                player.setOnPreparedListener(MediaPlayer::start);
                player.setOnCompletionListener(mp -> stopActivePlayer());
                player.prepareAsync();
                activePlayer = player;
                activePlayerMessageId = m.getId();
                activePlayButton = playButton;
                playButton.setImageResource(R.drawable.ic_pause);
            } catch (IOException e) {
                Toast.makeText(v.getContext(), "Sesli mesaj oynatılamadı.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String formatDuration(long ms) {
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
    }

    private String renderBody(MessageModel m) {
        if (m.getType() == null) {
            return m.getText() != null ? m.getText() : "";
        }
        switch (m.getType()) {
            case "image":
                return m.getText() != null ? m.getText() : "";
            case "video": return "\uD83C\uDFA5 [Video] (bu sürümde görüntülenemiyor)";
            case "voice": return "";
            case "file": return "\uD83D\uDCCE " + (m.getFileName() != null ? m.getFileName() : "Dosya") + formatSize(m.getFileSize()) + "\n(açmak için dokun)";
            default: return m.getText() != null ? m.getText() : "";
        }
    }

    private String formatSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return " (" + bytes + " B)";
        if (bytes < 1024 * 1024) return " (" + (bytes / 1024) + " KB)";
        return " (" + String.format(Locale.getDefault(), "%.1f", bytes / (1024.0 * 1024.0)) + " MB)";
    }

    private String dayLabelFor(long timestampMillis) {
        if (timestampMillis <= 0) return null;

        Calendar msgCal = Calendar.getInstance();
        msgCal.setTimeInMillis(timestampMillis);

        Calendar today = Calendar.getInstance();
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);

        if (isSameDay(msgCal, today)) return "Bugün";
        if (isSameDay(msgCal, yesterday)) return "Dün";
        return dateFormat.format(msgCal.getTime());
    }

    private boolean isSameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        TextView label;
        HeaderVH(View itemView) {
            super(itemView);
            label = itemView.findViewById(R.id.date_header_label);
        }
    }

    static class SentVH extends RecyclerView.ViewHolder {
        TextView text, time;
        ImageView image, tick;
        View voiceRow;
        ImageButton voicePlayButton;
        TextView voiceDuration;
        View replyBox;
        TextView replySender, replyText;
        SentVH(View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.message_text);
            time = itemView.findViewById(R.id.message_time);
            image = itemView.findViewById(R.id.message_image);
            tick = itemView.findViewById(R.id.message_tick);
            voiceRow = itemView.findViewById(R.id.message_voice_row);
            voicePlayButton = itemView.findViewById(R.id.message_voice_play_button);
            voiceDuration = itemView.findViewById(R.id.message_voice_duration);
            replyBox = itemView.findViewById(R.id.reply_preview_box);
            replySender = itemView.findViewById(R.id.reply_preview_sender);
            replyText = itemView.findViewById(R.id.reply_preview_text);
        }
    }

    static class ReceivedVH extends RecyclerView.ViewHolder {
        TextView text, time, sender;
        ImageView image;
        View voiceRow;
        ImageButton voicePlayButton;
        TextView voiceDuration;
        View replyBox;
        TextView replySender, replyText;
        ReceivedVH(View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.message_text);
            time = itemView.findViewById(R.id.message_time);
            sender = itemView.findViewById(R.id.message_sender);
            image = itemView.findViewById(R.id.message_image);
            voiceRow = itemView.findViewById(R.id.message_voice_row);
            voicePlayButton = itemView.findViewById(R.id.message_voice_play_button);
            voiceDuration = itemView.findViewById(R.id.message_voice_duration);
            replyBox = itemView.findViewById(R.id.reply_preview_box);
            replySender = itemView.findViewById(R.id.reply_preview_sender);
            replyText = itemView.findViewById(R.id.reply_preview_text);
        }
    }
}
