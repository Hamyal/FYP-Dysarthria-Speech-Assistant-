package com.example.mya;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

public class RequestAdapter extends RecyclerView.Adapter<RequestAdapter.ViewHolder> {

    private final List<PatientRequest> items = new ArrayList<>();
    private OnRequestActionListener listener;

    interface OnRequestActionListener {
        void onAccept(PatientRequest request);
        void onReject(PatientRequest request);
    }

    void setListener(OnRequestActionListener listener) {
        this.listener = listener;
    }

    void setItems(List<PatientRequest> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_request, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        PatientRequest r = items.get(position);
        holder.patientName.setText(r.getPatientName());
        holder.patientEmail.setText(r.getPatientEmail());
        holder.patientAge.setText("Age: " + r.getPatientAge());
        holder.btnAccept.setOnClickListener(v -> {
            if (listener != null) listener.onAccept(r);
        });
        holder.btnReject.setOnClickListener(v -> {
            if (listener != null) listener.onReject(r);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView patientName, patientEmail, patientAge;
        MaterialButton btnAccept, btnReject;

        ViewHolder(View itemView) {
            super(itemView);
            patientName = itemView.findViewById(R.id.patientName);
            patientEmail = itemView.findViewById(R.id.patientEmail);
            patientAge = itemView.findViewById(R.id.patientAge);
            btnAccept = itemView.findViewById(R.id.btnAccept);
            btnReject = itemView.findViewById(R.id.btnReject);
        }
    }
}
