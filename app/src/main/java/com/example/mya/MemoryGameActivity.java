package com.example.mya;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.appbar.MaterialToolbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MemoryGameActivity extends AppCompatActivity {

    private static final int PAIRS = 8;
    private static final String[] SYMBOLS = {"🌻", "🌸", "🌺", "🍀", "⭐", "🌟", "🔵", "🟢"};
    private static final long FLIP_BACK_DELAY_MS = 800;

    private RecyclerView gridCards;
    private TextView movesText;
    private View btnPlayAgain;
    private MemoryAdapter adapter;
    private int moves;
    private int revealedIndex1 = -1;
    private int revealedIndex2 = -1;
    private boolean locked;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_memory_game);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        movesText = findViewById(R.id.movesText);
        gridCards = findViewById(R.id.gridCards);
        btnPlayAgain = findViewById(R.id.btnPlayAgain);

        gridCards.setLayoutManager(new GridLayoutManager(this, 4));
        startNewGame();

        btnPlayAgain.setOnClickListener(v -> startNewGame());
    }

    private void startNewGame() {
        moves = 0;
        revealedIndex1 = -1;
        revealedIndex2 = -1;
        locked = false;
        btnPlayAgain.setVisibility(View.GONE);

        List<String> values = new ArrayList<>();
        for (int i = 0; i < PAIRS; i++) {
            values.add(SYMBOLS[i]);
            values.add(SYMBOLS[i]);
        }
        Collections.shuffle(values);

        adapter = new MemoryAdapter(values, this::onCardClick);
        gridCards.setAdapter(adapter);
        updateMoves();
    }

    private void onCardClick(int index) {
        if (locked) return;
        if (adapter.isMatched(index) || adapter.isRevealed(index)) return;

        adapter.reveal(index);
        adapter.notifyItemChanged(index);

        if (revealedIndex1 == -1) {
            revealedIndex1 = index;
            updateMoves();
            return;
        }
        revealedIndex2 = index;
        moves++;
        updateMoves();

        if (adapter.getValue(revealedIndex1).equals(adapter.getValue(revealedIndex2))) {
            adapter.setMatched(revealedIndex1);
            adapter.setMatched(revealedIndex2);
            adapter.notifyItemChanged(revealedIndex1);
            adapter.notifyItemChanged(revealedIndex2);
            revealedIndex1 = -1;
            revealedIndex2 = -1;
            if (adapter.allMatched()) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    Toast.makeText(this, R.string.you_win, Toast.LENGTH_SHORT).show();
                    btnPlayAgain.setVisibility(View.VISIBLE);
                }, 400);
            }
        } else {
            locked = true;
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                adapter.hide(revealedIndex1);
                adapter.hide(revealedIndex2);
                adapter.notifyItemChanged(revealedIndex1);
                adapter.notifyItemChanged(revealedIndex2);
                revealedIndex1 = -1;
                revealedIndex2 = -1;
                locked = false;
            }, FLIP_BACK_DELAY_MS);
        }
    }

    private void updateMoves() {
        movesText.setText(getString(R.string.moves, moves));
    }

    private static class MemoryAdapter extends RecyclerView.Adapter<MemoryAdapter.ViewHolder> {
        private final List<String> values;
        private final boolean[] revealed;
        private final boolean[] matched;
        private final OnCardClickListener listener;

        interface OnCardClickListener { void onClick(int index); }

        MemoryAdapter(List<String> values, OnCardClickListener listener) {
            this.values = values;
            this.listener = listener;
            this.revealed = new boolean[values.size()];
            this.matched = new boolean[values.size()];
        }

        void reveal(int i) { revealed[i] = true; }
        void hide(int i) { revealed[i] = false; }
        void setMatched(int i) { matched[i] = true; }
        boolean isRevealed(int i) { return revealed[i]; }
        boolean isMatched(int i) { return matched[i]; }
        String getValue(int i) { return values.get(i); }

        boolean allMatched() {
            for (boolean m : matched) if (!m) return false;
            return true;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_memory_card, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            boolean rev = revealed[position];
            boolean mat = matched[position];
            holder.cardText.setText(rev || mat ? values.get(position) : "?");
            int colorId = mat ? R.color.primary_light : (rev ? R.color.primary_container : R.color.surface_container);
            holder.card.setCardBackgroundColor(ContextCompat.getColor(holder.itemView.getContext(), colorId));
            holder.itemView.setOnClickListener(v -> listener.onClick(position));
        }

        @Override
        public int getItemCount() { return values.size(); }

        static class ViewHolder extends RecyclerView.ViewHolder {
            MaterialCardView card;
            TextView cardText;
            ViewHolder(View itemView) {
                super(itemView);
                card = (MaterialCardView) itemView;
                cardText = itemView.findViewById(R.id.cardText);
            }
        }
    }
}
