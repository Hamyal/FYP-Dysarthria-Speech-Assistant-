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

public class PatientsAdapter extends RecyclerView.Adapter<PatientsAdapter.ViewHolder> {

    private final List<Patient> items = new ArrayList<>();
    private OnPatientMessageListener listener;

    interface OnPatientMessageListener {
        void onMessage(Patient patient);
        void onAssignDrill(Patient patient);
        void onViewProgress(Patient patient);
    }

    void setListener(OnPatientMessageListener listener) {
        this.listener = listener;
    }

    void setItems(List<Patient> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_patient_chat, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Patient p = items.get(position);
        holder.patientName.setText(p.getFullName());
        holder.patientEmail.setText(p.getEmail());
        holder.btnMessage.setOnClickListener(v -> {
            if (listener != null) listener.onMessage(p);
        });
        holder.btnAssignDrill.setOnClickListener(v -> {
            if (listener != null) listener.onAssignDrill(p);
        });
        holder.btnViewProgress.setOnClickListener(v -> {
            if (listener != null) listener.onViewProgress(p);
        });
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onMessage(p);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView patientName, patientEmail;
        MaterialButton btnMessage, btnAssignDrill, btnViewProgress;

        ViewHolder(View itemView) {
            super(itemView);
            patientName = itemView.findViewById(R.id.patientName);
            patientEmail = itemView.findViewById(R.id.patientEmail);
            btnMessage = itemView.findViewById(R.id.btnMessage);
            btnAssignDrill = itemView.findViewById(R.id.btnAssignDrill);
            btnViewProgress = itemView.findViewById(R.id.btnViewProgress);
        }
    }
}
