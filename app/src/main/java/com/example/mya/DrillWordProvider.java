package com.example.mya;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Phoneme and word-based drill content for dysarthria speech therapy.
 * Easy: phonemes (single sounds / syllables) | Medium: complete words & short phrases (e.g. hot dog) | Hard: complex words & longer phrases
 * Level progression: 80%+ accuracy → advance. Start at Easy.
 */
public class DrillWordProvider {

    private static final Random RAND = new Random();

    // Easy: phonemes – single sounds and simple syllables (focus on articulation)
    private static final String[] EASY_PHONEMES = {
        "ah", "ee", "oo", "oh", "uh", "eh", "ih", "ay", "ow",
        "p", "b", "t", "d", "k", "g", "m", "n", "f", "v", "s", "z", "h", "w", "l", "r",
        "pa", "ba", "ta", "da", "ka", "ga", "ma", "na", "fa", "va", "sa", "za",
        "la", "ra", "wa", "ha", "ah-pa", "ee-me", "oo-m", "bah", "dah", "gah",
        "puh", "buh", "tuh", "duh", "muh", "nuh", "luh", "ruh"
    };

    // Medium: complete words and short phrases (e.g. hot dog, big cat)
    private static final String[] MEDIUM_WORDS = {
        "hot dog", "big cat", "red ball", "good job", "run fast", "blue sky",
        "nice day", "top hat", "bed time", "sun set", "ice cream", "bus stop",
        "phone call", "tea cup", "dog run", "cat nap", "fish tank", "book shelf",
        "door bell", "light bulb", "rain coat", "sun shine", "night light", "day dream",
        "home work", "foot ball", "bath tub", "hair cut", "back pack", "black bird",
        "sweet corn", "cold milk", "warm soup", "fast car", "tall tree", "small house",
        "happy face", "clean hands", "soft pillow", "new shoes", "old friend",
        "high five", "slow walk", "quick step", "loud voice", "quiet room"
    };

    // Hard: complex words and longer phrases
    private static final String[] HARD_WORDS = {
        "splash", "string", "strong", "through", "throat", "thread", "thrilled",
        "beautiful", "television", "comfortable", "different", "important",
        "exercise", "medicine", "vegetable", "adventure", "remember", "umbrella",
        "elephant", "hospital", "restaurant", "question", "situation", "conversation",
        "How are you today?", "I feel much better.", "See you next time.",
        "Practice makes perfect.", "Take your time.", "Speak clearly and slowly."
    };

    public static List<String> getRandomWords(String difficulty, int count) {
        String[] words = getWordsForDifficulty(difficulty);
        List<String> list = new ArrayList<>();
        for (String w : words) list.add(w);
        Collections.shuffle(list, RAND);
        int n = Math.min(count, list.size());
        return new ArrayList<>(list.subList(0, n));
    }

    public static String getRandomWordsAsString(String difficulty, int count, String separator) {
        List<String> words = getRandomWords(difficulty, count);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) sb.append(separator);
            sb.append(words.get(i));
        }
        return sb.toString();
    }

    private static String[] getWordsForDifficulty(String difficulty) {
        if (difficulty == null) difficulty = "easy";
        switch (difficulty.toLowerCase()) {
            case "hard": return HARD_WORDS;
            case "medium": return MEDIUM_WORDS;
            default: return EASY_PHONEMES;
        }
    }

    /**
     * Level progression: start Easy. 80%+ accuracy → advance to next level.
     * Easy + 80%+ → Medium; Medium + 80%+ → Hard. Therapist can override.
     * currentLevel: "easy"|"medium"|"hard"
     * accuracyPercent: 0–100
     */
    public static String computeNextLevel(String currentLevel, double accuracyPercent) {
        if (currentLevel == null) currentLevel = "easy";
        if (accuracyPercent < 80.0) return currentLevel;
        switch (currentLevel.toLowerCase()) {
            case "easy": return "medium";
            case "medium": return "hard";
            case "hard": return "hard";
            default: return currentLevel;
        }
    }
}
