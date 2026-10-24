package com.gathin.tvhome;

import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

public class ModuleCardsActivity extends android.app.Activity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_module_cards);
        RecyclerView rv = findViewById(R.id.recycler);
        rv.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        // Load module cards using Cards.discover and display them.
        // This activity is only shown when module cards feature is enabled.
        // Load cards on background thread to avoid blocking UI.
        new Thread(() -> {
            List<Cards.Card> cards = CardFeature.run(() -> Cards.discover(this, Collections.emptySet(), Cards.load(Cards.storeFile(this))), Collections.emptyList());
            // Update UI on main thread.
            runOnUiThread(() -> {
                rv.setAdapter(new CardAdapter(cards));
            });
        }).start();
    }
}

class CardAdapter extends RecyclerView.Adapter<CardAdapter.VH> {
    private final List<Cards.Card> cards;
    CardAdapter(List<Cards.Card> cards) { this.cards = cards; }
    static class VH extends RecyclerView.ViewHolder {
        final android.widget.TextView title;
        final android.widget.TextView state;
        final android.widget.TextView app;
        final android.widget.TextView pkg;
        final android.widget.TextView digest;
        VH(android.view.View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.title);
            state = itemView.findViewById(R.id.state);
            app = itemView.findViewById(R.id.app);
            pkg = itemView.findViewById(R.id.pkg);
            digest = itemView.findViewById(R.id.digest);
        }
    }
    @Override
    public VH onCreateViewHolder(ViewGroup parent, int viewType) {
        android.view.View v = android.view.LayoutInflater.from(parent.getContext()).inflate(R.layout.item_card_row, parent, false);
        return new VH(v);
    }
    @Override
    public void onBindViewHolder(VH holder, int position) {
        Cards.Card c = cards.get(position);
        holder.title.setText(c.label);
        holder.state.setText(c.state.name());
        holder.app.setText(c.appLabel);
        holder.pkg.setText(c.pkg);
        holder.digest.setText(Cards.longDigest(c.digest));
    }
    @Override
    public int getItemCount() { return cards.size(); }
}
