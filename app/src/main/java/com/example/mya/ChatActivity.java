package com.example.mya;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.Query;

import java.util.ArrayList;
import java.util.List;

public class ChatActivity extends AppCompatActivity {

    public static final String EXTRA_OTHER_UID = "other_uid";
    public static final String EXTRA_OTHER_NAME = "other_name";

    private String conversationId;
    private String currentUserId;
    private String currentUserName;
    private String otherUserId;
    private String otherUserName;

    private MessagesAdapter adapter;
    private RecyclerView messagesList;
    private TextInputEditText inputMessage;
    private View btnSend;
    private Query messagesQuery;
    private ChildEventListener messagesListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_chat);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        FirebaseUser user = FirebaseHelper.getCurrentUser();
        if (user == null) {
            finish();
            return;
        }
        currentUserId = user.getUid();
        currentUserName = user.getDisplayName();
        if (currentUserName == null || currentUserName.isEmpty()) {
            currentUserName = user.getEmail();
        }
        if (currentUserName == null) currentUserName = "Me";

        otherUserId = getIntent().getStringExtra(EXTRA_OTHER_UID);
        otherUserName = getIntent().getStringExtra(EXTRA_OTHER_NAME);
        if (otherUserId == null || otherUserName == null) {
            Toast.makeText(this, "Invalid chat.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        conversationId = FirebaseHelper.getConversationId(currentUserId, otherUserId);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setTitle(otherUserName);
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        messagesList = findViewById(R.id.messagesList);
        inputMessage = findViewById(R.id.inputMessage);
        btnSend = findViewById(R.id.btnSend);

        adapter = new MessagesAdapter(currentUserId);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        messagesList.setLayoutManager(lm);
        messagesList.setAdapter(adapter);

        btnSend.setOnClickListener(v -> sendMessage());
        loadCurrentUserNameFromDb();
        attachMessagesListener();
    }

    private void loadCurrentUserNameFromDb() {
        FirebaseHelper.getUserByUID(currentUserId, new com.google.firebase.database.ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    String name = snapshot.child("name").getValue(String.class);
                    if (name != null && !name.isEmpty()) currentUserName = name;
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private void attachMessagesListener() {
        messagesQuery = FirebaseHelper.getChatMessagesQuery(conversationId);
        messagesListener = new ChildEventListener() {
            @Override
            public void onChildAdded(@NonNull DataSnapshot snapshot, String previousChildName) {
                Message m = snapshotToMessage(snapshot);
                if (m != null) adapter.addMessage(m);
                scrollToBottom();
            }
            @Override
            public void onChildChanged(@NonNull DataSnapshot snapshot, String previousChildName) {}
            @Override
            public void onChildRemoved(@NonNull DataSnapshot snapshot) {}
            @Override
            public void onChildMoved(@NonNull DataSnapshot snapshot, String previousChildName) {}
            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        };
        messagesQuery.addChildEventListener(messagesListener);
    }

    private Message snapshotToMessage(DataSnapshot snap) {
        try {
            Message m = new Message();
            m.setId(snap.getKey());
            m.setSenderId(snap.child("senderId").getValue(String.class));
            m.setSenderName(snap.child("senderName").getValue(String.class));
            m.setReceiverId(snap.child("receiverId").getValue(String.class));
            m.setText(snap.child("text").getValue(String.class));
            Object ts = snap.child("timestamp").getValue();
            m.setTimestamp(ts instanceof Number ? ((Number) ts).longValue() : 0);
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    private void sendMessage() {
        String text = inputMessage.getText() != null ? inputMessage.getText().toString().trim() : "";
        if (TextUtils.isEmpty(text)) return;
        inputMessage.setText("");
        FirebaseHelper.sendChatMessage(conversationId, currentUserId, currentUserName, otherUserId, text);
        scrollToBottom();
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
        super.onDestroy();
        if (messagesQuery != null && messagesListener != null) {
            messagesQuery.removeEventListener(messagesListener);
        }
    }
}
