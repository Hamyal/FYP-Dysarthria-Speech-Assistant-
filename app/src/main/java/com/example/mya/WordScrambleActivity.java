package com.example.mya;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class WordScrambleActivity extends AppCompatActivity {

    private static final String[] WORDS = {
            "hello", "world", "happy", "calm", "peace", "hope", "smile",
            "breath", "relax", "strong", "brave", "kind", "good", "well",
            "today", "better", "trust", "light", "warm", "safe"
    };

    private TextView scrambledWord;
    private TextInputEditText userInput;
    private View btnCheck;
    private View btnNext;
    private TextView feedbackText;
    private TextView scoreText;

    private List<String> words;
    private int currentIndex;
    private int score;
    private boolean answered;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_word_scramble);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        scrambledWord = findViewById(R.id.scrambledWord);
        userInput = findViewById(R.id.userInput);
        btnCheck = findViewById(R.id.btnCheck);
        btnNext = findViewById(R.id.btnNext);
        feedbackText = findViewById(R.id.feedbackText);
        scoreText = findViewById(R.id.scoreText);

        words = new ArrayList<>(Arrays.asList(WORDS));
        Collections.shuffle(words);
        currentIndex = 0;
        score = 0;
        answered = false;

        showCurrentWord();
        updateScore();

        btnCheck.setOnClickListener(v -> checkAnswer());
        btnNext.setOnClickListener(v -> nextWord());
    }

    private String scramble(String word) {
        List<Character> chars = new ArrayList<>();
        for (char c : word.toCharArray()) chars.add(c);
        Collections.shuffle(chars);
        StringBuilder sb = new StringBuilder(chars.size());
        for (Character c : chars) sb.append(c);
        return sb.toString();
    }

    private void showCurrentWord() {
        if (currentIndex >= words.size()) {
            scrambledWord.setText(getString(R.string.you_win));
            userInput.setVisibility(View.GONE);
            btnCheck.setVisibility(View.GONE);
            btnNext.setVisibility(View.GONE);
            feedbackText.setVisibility(View.VISIBLE);
            feedbackText.setText(getString(R.string.score, score, words.size()));
            return;
        }
        String word = words.get(currentIndex);
        scrambledWord.setText(scramble(word));
        userInput.setText("");
        userInput.setVisibility(View.VISIBLE);
        btnCheck.setVisibility(View.VISIBLE);
        btnNext.setVisibility(View.GONE);
        feedbackText.setVisibility(View.GONE);
        answered = false;
    }

    private void checkAnswer() {
        if (answered) return;
        String expected = words.get(currentIndex).trim();
        String actual = userInput.getText() != null ? userInput.getText().toString().trim() : "";
        if (TextUtils.isEmpty(actual)) {
            Toast.makeText(this, "Type your answer first.", Toast.LENGTH_SHORT).show();
            return;
        }
        answered = true;
        boolean correct = expected.equalsIgnoreCase(actual);
        if (correct) score++;
        updateScore();
        feedbackText.setVisibility(View.VISIBLE);
        feedbackText.setText(correct ? getString(R.string.correct) : getString(R.string.try_again));
        feedbackText.setTextColor(ContextCompat.getColor(this, correct ? R.color.primary : R.color.error));
        btnCheck.setVisibility(View.GONE);
        btnNext.setVisibility(View.VISIBLE);
    }

    private void nextWord() {
        currentIndex++;
        showCurrentWord();
    }

    private void updateScore() {
        scoreText.setText(getString(R.string.score, score, words.size()));
    }
}
