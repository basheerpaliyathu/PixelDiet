/*
 * Copyright 2026 Basheer
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.pixeldiet.sample

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.pixeldiet.sample.databinding.ItemResultBinding
import java.util.Locale

class ResultAdapter : ListAdapter<CompressResult, ResultAdapter.VH>(Diff) {

    inner class VH(private val binding: ItemResultBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CompressResult) {
            when (item) {
                is CompressResult.Success -> bindSuccess(item)
                is CompressResult.Failure -> bindFailure(item)
            }
        }

        private fun bindSuccess(item: CompressResult.Success) {
            binding.tvError.visibility = View.GONE
            binding.ivBefore.visibility = View.VISIBLE
            binding.ivArrow.visibility = View.VISIBLE
            binding.ivAfter.visibility = View.VISIBLE
            binding.statsBlock.visibility = View.VISIBLE
            binding.chipSavings.visibility = View.VISIBLE

            // Thumbnails
            Glide.with(binding.ivBefore).load(item.sourceUri).centerCrop().into(binding.ivBefore)
            Glide.with(binding.ivAfter).load(item.outputFile).centerCrop().into(binding.ivAfter)

            // Savings chip — colour-coded
            binding.chipSavings.text = binding.root.context.getString(
                R.string.savings_fmt, item.savingsPct
            )
            val chipColor = when {
                item.savingsPct >= 60 -> 0xFF2E7D32.toInt() // dark green
                item.savingsPct >= 30 -> 0xFF558B2F.toInt() // medium green
                item.savingsPct > 0   -> 0xFFF57F17.toInt() // amber
                else                   -> 0xFFC62828.toInt() // red (grew)
            }
            binding.chipSavings.setTextColor(chipColor)

            // Stats
            binding.tvSizes.text = binding.root.context.getString(
                R.string.sizes_fmt, formatBytes(item.originalBytes), formatBytes(item.compressedBytes)
            )
            binding.tvFormat.text = binding.root.context.getString(R.string.format_label, item.formatLabel)
            binding.tvGear.text = binding.root.context.getString(R.string.gear_label, item.gearLabel)
            binding.tvDuration.text = binding.root.context.getString(R.string.duration_label, item.durationMs)

            // Path (truncated)
            binding.tvPath.text = item.outputFile.absolutePath
        }

        private fun bindFailure(item: CompressResult.Failure) {
            binding.ivBefore.visibility = View.GONE
            binding.ivArrow.visibility = View.GONE
            binding.ivAfter.visibility = View.GONE
            binding.statsBlock.visibility = View.GONE
            binding.chipSavings.visibility = View.GONE
            binding.tvPath.text = ""

            binding.tvError.visibility = View.VISIBLE
            binding.tvError.text = binding.root.context.getString(R.string.error_label, item.message)
        }

        private fun formatBytes(bytes: Long): String = when {
            bytes < 0           -> "?"
            bytes < 1024        -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
            else                -> String.format(Locale.US, "%.2f MB", bytes / (1024f * 1024f))
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    private object Diff : DiffUtil.ItemCallback<CompressResult>() {
        override fun areItemsTheSame(a: CompressResult, b: CompressResult) = a === b
        override fun areContentsTheSame(a: CompressResult, b: CompressResult) = a == b
    }
}
