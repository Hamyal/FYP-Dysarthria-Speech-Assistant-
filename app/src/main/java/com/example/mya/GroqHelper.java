package com.example.mya;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Call Groq's chat API directly from Java (OpenAI-compatible endpoint).
 * Use this when you have GROQ_API_KEY in BuildConfig; otherwise the app uses the VocalAid API.
 *
 * Security: Do not commit your API key. Use gradle.properties (add to .gitignore) or
 * buildConfigField only for development.
 */
public final class GroqHelper {

    private static final String GROQ_CHAT_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String DEFAULT_MODEL = "llama-3.1-8b-instant";

    public interface Callback {
        void onSuccess(String content);
        void onError(String message);
    }

    /** One message in a conversation (role + content). */
    public static class Message {
        public final String role; // "user" or "assistant"
        public final String content;
        public Message(String role, String content) {
            this.role = role;
            this.content = content != null ? content : "";
        }
    }

    /**
     * Send a chat request to Groq and return the assistant's reply via callback.
     *
     * @param apiKey       Groq API key (required)
     * @param model        Model id, e.g. "llama-3.1-8b-instant"; null = use default
     * @param systemPrompt Optional system message; can be null
     * @param userPrompt   User message (required)
     * @param callback     Called on main thread with result or error
     */
    public static void chat(@NonNull String apiKey,
                            String model,
                            String systemPrompt,
                            @NonNull String userPrompt,
                            @NonNull Callback callback) {
        if (apiKey.isEmpty()) {
            callback.onError("Groq API key is empty");
            return;
        }

        new Thread(() -> {
            try {
                JSONArray messages = new JSONArray();
                if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
                    messages.put(new JSONObject()
                            .put("role", "system")
                            .put("content", systemPrompt.trim()));
                }
                messages.put(new JSONObject()
                        .put("role", "user")
                        .put("content", userPrompt));

                JSONObject body = new JSONObject()
                        .put("model", model != null && !model.isEmpty() ? model : DEFAULT_MODEL)
                        .put("messages", messages)
                        .put("temperature", 0.3);

                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(25, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .build();

                Request request = new Request.Builder()
                        .url(GROQ_CHAT_URL)
                        .addHeader("Authorization", "Bearer " + apiKey)
                        .addHeader("Content-Type", "application/json")
                        .post(RequestBody.create(body.toString(), MediaType.parse("application/json; charset=utf-8")))
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "";

                if (!response.isSuccessful()) {
                    postError(callback, "Groq API error: " + response.code() + " " + responseBody);
                    return;
                }

                JSONObject json = new JSONObject(responseBody);
                JSONArray choices = json.optJSONArray("choices");
                if (choices == null || choices.length() == 0) {
                    postError(callback, "No response from Groq");
                    return;
                }
                String content = choices.getJSONObject(0)
                        .optJSONObject("message")
                        .optString("content", "")
                        .trim();
                if (content.isEmpty()) {
                    postError(callback, "Empty response from Groq");
                    return;
                }
                postSuccess(callback, content);
            } catch (Exception e) {
                postError(callback, e.getMessage() != null ? e.getMessage() : "Groq request failed");
            }
        }).start();
    }

    private static void postSuccess(Callback callback, String content) {
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.post(() -> callback.onSuccess(content));
    }

    private static void postError(Callback callback, String message) {
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.post(() -> callback.onError(message));
    }

    /**
     * Multi-turn chat: send conversation history + new user message, get assistant reply.
     *
     * @param apiKey        Groq API key
     * @param model         model id or null for default
     * @param systemPrompt  system message (dysarthria agent prompt)
     * @param conversation  previous messages in order (user/assistant)
     * @param newUserMessage the latest user message
     * @param callback      result on main thread
     */
    public static void chatWithHistory(@NonNull String apiKey,
                                      String model,
                                      String systemPrompt,
                                      @NonNull java.util.List<Message> conversation,
                                      @NonNull String newUserMessage,
                                      @NonNull Callback callback) {
        if (apiKey.isEmpty()) {
            callback.onError("Groq API key is empty");
            return;
        }
        new Thread(() -> {
            try {
                JSONArray messages = new JSONArray();
                if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
                    messages.put(new JSONObject().put("role", "system").put("content", systemPrompt.trim()));
                }
                for (Message m : conversation) {
                    if (m.content.isEmpty()) continue;
                    messages.put(new JSONObject().put("role", m.role).put("content", m.content));
                }
                messages.put(new JSONObject().put("role", "user").put("content", newUserMessage));

                JSONObject body = new JSONObject()
                        .put("model", model != null && !model.isEmpty() ? model : DEFAULT_MODEL)
                        .put("messages", messages)
                        .put("temperature", 0.5);

                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(25, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .build();

                Request request = new Request.Builder()
                        .url(GROQ_CHAT_URL)
                        .addHeader("Authorization", "Bearer " + apiKey)
                        .addHeader("Content-Type", "application/json")
                        .post(RequestBody.create(body.toString(), MediaType.parse("application/json; charset=utf-8")))
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    postError(callback, "Groq API error: " + response.code());
                    return;
                }
                JSONObject json = new JSONObject(responseBody);
                JSONArray choices = json.optJSONArray("choices");
                if (choices == null || choices.length() == 0) {
                    postError(callback, "No response from Groq");
                    return;
                }
                String content = choices.getJSONObject(0).optJSONObject("message").optString("content", "").trim();
                if (content.isEmpty()) {
                    postError(callback, "Empty response from Groq");
                    return;
                }
                postSuccess(callback, content);
            } catch (Exception e) {
                postError(callback, e.getMessage() != null ? e.getMessage() : "Groq request failed");
            }
        }).start();
    }
}
