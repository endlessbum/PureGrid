package tv.puregrid.launcher;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * The module cards list (docs/specs/card.md §7): every declared card with its state, its app and
 * the full signing key. OK toggles the card between shown and hidden through the same
 * {@link Cards#decide} the on-screen prompt uses, so one decision store backs both.
 */
public class ModuleCardsActivity extends android.app.Activity {
    private RecyclerView recycler;
    private TextView empty;
    /** One worker owned by the activity: discovery and store writes are serialized, and the
     *  activity is not pinned by a bare Thread that outlives the screen. */
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // A launcher sub-screen: MainActivity released KEEP_SCREEN_ON in its onPause, so take the
        // flag here — otherwise the system screen timeout naps the TV while this screen is open.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_module_cards);
        recycler = findViewById(R.id.recycler);
        empty = findViewById(R.id.empty);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        reload();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bg.shutdown();
    }

    /** Runs work on the background worker, silently skipping it once the activity is torn down. */
    void bg(Runnable r) {
        try {
            bg.execute(r);
        } catch (RejectedExecutionException dead) {
            // shutdown() already ran; there is no list left to publish to.
        }
    }

    /** Discovers the cards on a background thread (PackageManager + signing digests are not
     *  UI-thread work) and rebuilds the list; runs again after every decision. */
    private void reload() {
        bg(() -> {
            Set<String> hiddenApps = new HashSet<>(Config.load(this).hiddenApps);
            List<Cards.Card> cards = CardFeature.run(
                    () -> Cards.discover(this, hiddenApps, Cards.load(Cards.storeFile(this))),
                    Collections.emptyList());
            // Which shown cards actually take one of the on-screen slots: beyond MAX_SHOWN the
            // rest stay "over the limit" — listed, but not rendered on the home screen.
            Set<String> onScreen = new HashSet<>();
            for (Cards.Card c : Cards.shown(cards)) onScreen.add(c.pkg);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (cards.isEmpty()) {
                    recycler.setVisibility(View.GONE);
                    empty.setVisibility(View.VISIBLE);
                    return;
                }
                recycler.setVisibility(View.VISIBLE);
                empty.setVisibility(View.GONE);
                recycler.setAdapter(new CardAdapter(this, cards, onScreen));
            });
        });
    }

    void onCardChanged() {
        reload();
    }
}

class CardAdapter extends RecyclerView.Adapter<CardAdapter.VH> {
    private final ModuleCardsActivity owner;
    private final List<Cards.Card> cards;
    /** The packages whose shown card really renders on the home screen (within the limit). */
    private final Set<String> onScreen;
    private final File store;

    CardAdapter(ModuleCardsActivity owner, List<Cards.Card> cards, Set<String> onScreen) {
        this.owner = owner;
        this.cards = cards;
        this.onScreen = onScreen;
        this.store = Cards.storeFile(owner);
    }

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
        android.view.View v = android.view.LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_card_row, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(VH holder, int position) {
        Cards.Card c = cards.get(position);
        holder.title.setText(c.label);
        holder.state.setText(stateText(c));
        holder.app.setText(owner.getString(R.string.card_app_name, c.appLabel));
        holder.pkg.setText(c.pkg);
        holder.digest.setText(Cards.longDigest(c.digest));
        // OK toggles the card: a hidden one is shown, anything else (shown, over the limit) is
        // hidden. The store write is the same one the on-screen prompt makes; the list then
        // re-discovers so every state line comes from the store, not from a local guess. The
        // write is file I/O, so it (and the refresh that reads it back) stays off the UI thread.
        holder.itemView.setOnClickListener(v -> {
            boolean show = c.state == Cards.State.HIDDEN;
            owner.bg(() -> {
                if (!Cards.decide(store, c, show)) return;
                owner.runOnUiThread(() -> {
                    if (owner.isDestroyed()) return;
                    owner.onCardChanged();
                });
            });
        });
    }

    private int stateText(Cards.Card c) {
        switch (c.state) {
            case SHOWN:
                return onScreen.contains(c.pkg)
                        ? R.string.card_state_shown : R.string.card_state_over_limit;
            case RESIGNED:
                return R.string.card_state_new_key;
            case RELABELLED:
                return R.string.card_state_new_label;
            case HIDDEN:
                return R.string.card_state_hidden;
            case NEW:
            default:
                return R.string.card_state_new;
        }
    }

    @Override
    public int getItemCount() { return cards.size(); }
}
