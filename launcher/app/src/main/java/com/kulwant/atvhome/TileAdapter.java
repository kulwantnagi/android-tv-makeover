package com.kulwant.atvhome;

import android.content.Context;
import android.graphics.Outline;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Banner tiles for the dock and the grid. Focus is driven by HomeActivity, never by Android focus. */
final class TileAdapter extends RecyclerView.Adapter<TileAdapter.Holder> {

    static final float FOCUS_SCALE = 1.14f;

    final List<AppRepo.App> apps = new ArrayList<>();
    private final int cellW, cardW, cardH, labelSpace;
    private final boolean labels;
    private int focused = -1;

    TileAdapter(int cellW, int cardW, int cardH, int labelSpace, boolean labels) {
        this.cellW = cellW;
        this.cardW = cardW;
        this.cardH = cardH;
        this.labelSpace = labelSpace;
        this.labels = labels;
    }

    void setApps(List<AppRepo.App> list) {
        apps.clear();
        apps.addAll(list);
        notifyDataSetChanged();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView card;
        final TextView label;

        Holder(View v, ImageView card, TextView label) {
            super(v);
            this.card = card;
            this.label = label;
        }
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int type) {
        Context c = parent.getContext();
        FrameLayout root = new FrameLayout(c);
        root.setLayoutParams(new RecyclerView.LayoutParams(cellW, cardH + labelSpace));
        root.setClipChildren(false);
        root.setFocusable(false);

        ImageView card = new ImageView(c);
        card.setId(R.id.card);
        card.setScaleType(ImageView.ScaleType.CENTER_CROP);
        final float radius = Ui.dp(c, 10);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        card.setClipToOutline(true);
        card.setForeground(Ui.rounded(0, radius, Math.max(1, Ui.dp(c, 1)), 0x1FFFFFFF));   // hairline edge for dark banners
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(cardW, cardH, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        root.addView(card, lp);

        TextView label = Ui.text(c, 400, 13, 0xEEFFFFFF);
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0f);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        llp.topMargin = cardH + Ui.dp(c, 12);
        root.addView(label, llp);
        return new Holder(root, card, label);
    }

    @Override
    public void onBindViewHolder(Holder h, int pos) {
        AppRepo.App a = apps.get(pos);
        h.card.setImageBitmap(a.banner);
        h.label.setText(a.label);
        boolean f = pos == focused;
        h.card.setScaleX(f ? FOCUS_SCALE : 1f);
        h.card.setScaleY(f ? FOCUS_SCALE : 1f);
        h.label.setAlpha(f && labels ? 1f : 0f);
    }

    @Override
    public int getItemCount() {
        return apps.size();
    }

    /** Animate focus from the old tile to {@code pos} (-1 = none). */
    void setFocused(RecyclerView rv, int pos) {
        if (pos == focused) return;
        animate(rv, focused, false);
        focused = pos;
        animate(rv, pos, true);
    }

    private void animate(RecyclerView rv, int pos, boolean on) {
        if (pos < 0) return;
        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(pos);
        if (!(vh instanceof Holder)) return;
        Holder h = (Holder) vh;
        float s = on ? FOCUS_SCALE : 1f;
        h.card.animate().scaleX(s).scaleY(s).setDuration(220)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        h.label.animate().alpha(on && labels ? 1f : 0f).setDuration(on ? 220 : 120).start();
    }
}
