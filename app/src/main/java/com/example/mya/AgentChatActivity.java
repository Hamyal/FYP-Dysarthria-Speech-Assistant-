package com.example.mya;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * ChatGPT-style conversation with an AI agent about dysarthria (Groq).
 * Separate from AI Summary: this is free-form Q&A; AI Summary is patient-history summary + suggestions.
 */
public class AgentChatActivity extends AppCompatActivity {

    private static final String AGENT_SYSTEM_PROMPT = "You are a friendly, knowledgeable assistant inside the VocalAid app, focused on dysarthria (motor speech disorder) and speech therapy. "
            + "Answer questions about dysarthria, what it is, causes, how it affects speech, and what helps. "
            + "Explain speech therapy concepts in simple terms. Suggest simple exercises and tips for clearer speech. "
            + "Be supportive and encouraging. Keep replies concise (a few short paragraphs or bullet points). Use clear language. Do not use markdown code blocks.";

    private final List<ChatItem> messages = new ArrayList<>();
    private AgentMessagesAdapter adapter;
    private RecyclerView messagesList;
    private TextInputEditText inputMessage;
    private View btnSend;
    private View progressBar;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_agent_chat);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        messagesList = findViewById(R.id.messagesList);
        inputMessage = findViewById(R.id.inputMessage);
        btnSend = findViewById(R.id.btnSend);
        progressBar = findViewById(R.id.progressBar);

        if (progressBar == null) progressBar = findViewById(android.R.id.progress);

        adapter = new AgentMessagesAdapter(messages);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        messagesList.setLayoutManager(lm);
        messagesList.setAdapter(adapter);

        btnSend.setOnClickListener(v -> sendMessage());
    }

    private void sendMessage() {
        String text = inputMessage.getText() != null ? inputMessage.getText().toString().trim() : "";
        if (TextUtils.isEmpty(text)) return;
        inputMessage.setText("");
        addMessage("user", text);
        scrollToBottom();
        btnSend.setEnabled(false);
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);

        String groqKey = BuildConfig.GROQ_API_KEY != null ? BuildConfig.GROQ_API_KEY.trim() : "";
        if (!groqKey.isEmpty()) {
            List<GroqHelper.Message> history = new ArrayList<>();
            for (ChatItem m : messages) {
                if (m.role.equals("user") || m.role.equals("assistant"))
                    history.add(new GroqHelper.Message(m.role, m.content));
            }
            // Remove the last (current) user message from history for the API; we'll add it in the call
            if (!history.isEmpty() && "user".equals(history.get(history.size() - 1).role)) {
                history.remove(history.size() - 1);
            }
            GroqHelper.chatWithHistory(groqKey, null, AGENT_SYSTEM_PROMPT, history, text, new GroqHelper.Callback() {
                @Override
                public void onSuccess(String content) {
                    mainHandler.post(() -> {
                        addMessage("assistant", content);
                        scrollToBottom();
                        btnSend.setEnabled(true);
                        if (progressBar != null) progressBar.setVisibility(View.GONE);
                    });
                }
                @Override
                public void onError(String message) {
                    mainHandler.post(() -> {
                        addMessage("assistant", getString(R.string.agent_error) + " " + message);
                        scrollToBottom();
                        btnSend.setEnabled(true);
                        if (progressBar != null) progressBar.setVisibility(View.GONE);
                        Toast.makeText(AgentChatActivity.this, message, Toast.LENGTH_SHORT).show();
                    });
                }
            });
            return;
        }

        // Fallback: VocalAid API /ai/chat
        JSONArray arr = new JSONArray();
        for (ChatItem m : messages) {
            if (m.role.equals("user") || m.role.equals("assistant")) {
                try {
                    arr.put(new JSONObject().put("role", m.role).put("content", m.content));
                } catch (Exception ignored) {}
            }
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("messages", arr);
            payload.put("role", "patient");
            String url = BuildConfig.VOCALAID_API_URL + "/ai/chat";
            executor.execute(() -> {
                try {
                    OkHttpClient client = new OkHttpClient.Builder()
                            .connectTimeout(25, TimeUnit.SECONDS)
                            .readTimeout(60, TimeUnit.SECONDS)
                            .build();
                    RequestBody body = RequestBody.create(
                            payload.toString(),
                            MediaType.parse("application/json; charset=utf-8"));
                    Request request = new Request.Builder().url(url).post(body).build();
                    Response response = client.newCall(request).execute();
                    String bodyStr = response.body() != null ? response.body().string() : "";
                    mainHandler.post(() -> {
                        btnSend.setEnabled(true);
                        if (progressBar != null) progressBar.setVisibility(View.GONE);
                        try {
                            if (!response.isSuccessful()) {
                                addMessage("assistant", getString(R.string.agent_error) + " " + bodyStr);
                                scrollToBottom();
                                return;
                            }
                            JSONObject json = new JSONObject(bodyStr);
                            String reply = json.optString("reply", "");
                            if (reply.isEmpty()) reply = json.optString("error", getString(R.string.agent_error));
                            addMessage("assistant", reply);
                            scrollToBottom();
                        } catch (Exception e) {
                            addMessage("assistant", getString(R.string.agent_error) + " " + e.getMessage());
                            scrollToBottom();
                        }
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        btnSend.setEnabled(true);
                        if (progressBar != null) progressBar.setVisibility(View.GONE);
                        addMessage("assistant", getString(R.string.agent_error) + " " + (e.getMessage() != null ? e.getMessage() : ""));
                        scrollToBottom();
                        Toast.makeText(AgentChatActivity.this, e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
                }
            });
        } catch (Exception e) {
            btnSend.setEnabled(true);
            if (progressBar != null) progressBar.setVisibility(View.GONE);
            addMessage("assistant", getString(R.string.agent_error) + " " + e.getMessage());
            scrollToBottom();
        }
    }

    private void addMessage(String role, String content) {
        messages.add(new ChatItem(role, content));
        adapter.notifyItemInserted(messages.size() - 1);
    }

    private void scrollToBottom() {
        messagesList.postDelayed(() -> {
            if (adapter.getItemCount() > 0) {
                messagesList.smoothScrollToPosition(adapter.getItemCount() - 1);
            }
        }, 100);
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    private static class ChatItem {
        final String role;
        final String content;
        ChatItem(String role, String content) {
            this.role = role;
            this.content = content != null ? content : "";
        }
    }

    private static class AgentMessagesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int VIEW_SENT = 1;
        private static final int VIEW_RECEIVED = 2;
        private final List<ChatItem> items;

        AgentMessagesAdapter(List<ChatItem> items) {
            this.items = items;
        }

        @Override
        public int getItemViewType(int position) {
            return "user".equals(items.get(position).role) ? VIEW_SENT : VIEW_RECEIVED;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == VIEW_SENT) {
                View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message_sent, parent, false);
                return new VH(v);
            } else {
                View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message_received, parent, false);
                return new VH(v);
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ((VH) holder).text.setText(items.get(position).content);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView text;
            VH(View itemView) {
                super(itemView);
                text = itemView.findViewById(R.id.text);
            }
        }
    }
}
