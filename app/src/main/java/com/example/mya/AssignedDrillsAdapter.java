package com.example.mya;

import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.List;

public class AssignedDrillsAdapter extends RecyclerView.Adapter<AssignedDrillsAdapter.VH> {

    private final List<AssignedDrill> items;
    private final OnRecordAnalyzeListener onRecordAnalyze;
    private final OnMarkCompleteListener onMarkComplete;

    public interface OnRecordAnalyzeListener { void onRecordAnalyze(AssignedDrill drill); }
    public interface OnMarkCompleteListener { void onMarkComplete(AssignedDrill drill); }

    public AssignedDrillsAdapter(List<AssignedDrill> items,
                                 OnRecordAnalyzeListener onRecordAnalyze,
                                 OnMarkCompleteListener onMarkComplete) {
        this.items = items;
        this.onRecordAnalyze = onRecordAnalyze;
        this.onMarkComplete = onMarkComplete;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_assigned_drill, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        AssignedDrill d = items.get(position);
        holder.title.setText(d.getTitle() != null && !d.getTitle().isEmpty() ? d.getTitle() : "Drill");
        holder.instructions.setText(d.getInstructions() != null ? d.getInstructions() : "");
        holder.instructions.setVisibility((d.getInstructions() != null && !d.getInstructions().isEmpty()) ? View.VISIBLE : View.GONE);
        holder.targetWords.setText(d.getTargetWords() != null ? d.getTargetWords() : "");
        holder.targetWords.setVisibility((d.getTargetWords() != null && !d.getTargetWords().isEmpty()) ? View.VISIBLE : View.GONE);
        String diff = d.getDifficulty();
        holder.difficultyBadge.setText(diff != null && diff.length() > 0
                ? diff.substring(0, 1).toUpperCase() + diff.substring(1) : "Easy");
        int barColor = R.color.difficulty_easy;
        if ("medium".equalsIgnoreCase(d.getDifficulty())) barColor = R.color.difficulty_medium;
        else if ("hard".equalsIgnoreCase(d.getDifficulty())) barColor = R.color.difficulty_hard;
        holder.difficultyBar.setBackground(new ColorDrawable(ContextCompat.getColor(holder.itemView.getContext(), barColor)));
        holder.status.setText(d.isCompleted()
                ? holder.itemView.getContext().getString(R.string.completed)
                : (d.getDysarthriaPrediction() != null && !d.getDysarthriaPrediction().isEmpty()
                ? d.getDysarthriaPrediction() + (d.getDysarthriaScore() != null ? " (" + String.format("%.0f%%", d.getDysarthriaScore() * 100) + ")" : "")
                : ""));
        holder.btnRecordAnalyze.setOnClickListener(v -> {
            if (onRecordAnalyze != null) onRecordAnalyze.onRecordAnalyze(d);
        });
        holder.btnMarkComplete.setOnClickListener(v -> {
            if (onMarkComplete != null) onMarkComplete.onMarkComplete(d);
        });
        holder.btnMarkComplete.setVisibility(d.isCompleted() ? View.GONE : View.VISIBLE);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView title, instructions, status, targetWords, difficultyBadge;
        View difficultyBar;
        MaterialButton btnRecordAnalyze, btnMarkComplete;

        VH(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.drillTitle);
            instructions = itemView.findViewById(R.id.drillInstructions);
            targetWords = itemView.findViewById(R.id.drillTargetWords);
            status = itemView.findViewById(R.id.drillStatus);
            difficultyBar = itemView.findViewById(R.id.difficultyBar);
            difficultyBadge = itemView.findViewById(R.id.difficultyBadge);
            btnRecordAnalyze = itemView.findViewById(R.id.btnRecordAnalyze);
            btnMarkComplete = itemView.findViewById(R.id.btnMarkComplete);
        }
    }
}
