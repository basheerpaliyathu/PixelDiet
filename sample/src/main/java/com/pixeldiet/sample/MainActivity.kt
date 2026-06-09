/*
 * Copyright 2026 Basheer
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.pixeldiet.sample

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.pixeldiet.compressor.OutputFormat
import com.pixeldiet.compressor.PixelDiet
import com.pixeldiet.sample.databinding.ActivityMainBinding
import com.pixeldiet.sample.databinding.PartSegBinding
import com.pixeldiet.sample.databinding.ViewResultCardBinding
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis

/**
 * PixelDiet sample — "Acid" redesign (Direction A).
 *
 * Pixel-recreated from the Claude Design handoff while keeping the app fully functional:
 * Photo Picker input, real PixelDiet compression, before/after result with savings metrics.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val selectedUris = mutableListOf<Uri>()
    private var format: OutputFormat = OutputFormat.WEBP_LOSSY
    private var gear: Int = PixelDiet.GEAR_FIRST
    private var hardCapEnabled = false

    private val pickSingle = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> uri?.let { setSelection(listOf(it)) } }

    private val pickMultiple = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris: List<Uri> -> if (uris.isNotEmpty()) setSelection(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupFormatSegments()
        setupGearSegments()
        setupHardCap()
        setupActions()
        applyCtaGlow()
        updateCompressEnabled()
    }

    // ── Segments ──────────────────────────────────────────────────────────────

    private data class Seg<T>(val view: PartSegBinding, val label: String, val em: String?, val value: T)

    private lateinit var formatSegs: List<Seg<OutputFormat>>
    private lateinit var gearSegs: List<Seg<Int>>

    private fun setupFormatSegments() {
        formatSegs = listOf(
            Seg(binding.segJpeg, "JPEG", null, OutputFormat.JPEG),
            Seg(binding.segWebpLossy, "WebP Lossy", null, OutputFormat.WEBP_LOSSY),
            Seg(binding.segWebpLossless, "WebP Lossless", null, OutputFormat.WEBP_LOSSLESS),
            Seg(binding.segPng, "PNG", null, OutputFormat.PNG),
        )
        formatSegs.forEach { seg ->
            seg.view.segLabel.text = seg.label
            seg.view.root.setOnClickListener {
                format = seg.value
                renderFormatSelection()
            }
        }
        renderFormatSelection()
    }

    private fun setupGearSegments() {
        gearSegs = listOf(
            Seg(binding.segSmart, "Smart", "3rd", PixelDiet.GEAR_THIRD),
            Seg(binding.segAggressive, "Aggressive", "1st", PixelDiet.GEAR_FIRST),
            Seg(binding.segCustom, "Custom", null, PixelDiet.GEAR_CUSTOM),
        )
        gearSegs.forEach { seg ->
            seg.view.segLabel.text = seg.label
            if (seg.em != null) {
                seg.view.segEm.text = seg.em
                seg.view.segEm.visibility = View.VISIBLE
            }
            seg.view.root.setOnClickListener {
                gear = seg.value
                renderGearSelection()
            }
        }
        renderGearSelection()
    }

    private fun renderFormatSelection() =
        formatSegs.forEach { styleSeg(it.view, it.value == format, hasEm = false) }

    private fun renderGearSelection() =
        gearSegs.forEach { styleSeg(it.view, it.value == gear, hasEm = it.em != null) }

    private fun styleSeg(seg: PartSegBinding, active: Boolean, hasEm: Boolean) {
        seg.root.setBackgroundResource(if (active) R.drawable.seg_active else R.drawable.seg_default)
        seg.segTick.visibility = if (active) View.VISIBLE else View.GONE
        val labelColor = if (active) R.color.acid_c else R.color.text_seg
        seg.segLabel.setTextColor(ContextCompat.getColor(this, labelColor))
        seg.segLabel.setTextAppearanceFont(if (active) R.font.sora_semibold else R.font.sora_medium)
        if (hasEm) {
            // On narrow screens the active chip's check needs the room the "em" would take,
            // so the em (3rd / 1st) shows only on the inactive chips.
            seg.segEm.visibility = if (active) View.GONE else View.VISIBLE
            seg.segEm.setTextColor(ContextCompat.getColor(this, R.color.muted2))
        }
    }

    // ── Hard cap ──────────────────────────────────────────────────────────────

    private fun setupHardCap() {
        binding.toggleHardCap.setOnClickListener {
            hardCapEnabled = !hardCapEnabled
            binding.checkboxHardCap.setBackgroundResource(
                if (hardCapEnabled) R.drawable.checkbox_checked else R.drawable.checkbox_default
            )
            binding.checkboxTick.visibility = if (hardCapEnabled) View.VISIBLE else View.GONE
            binding.etHardCap.isEnabled = hardCapEnabled
            if (!hardCapEnabled) binding.etHardCap.text?.clear()
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private fun setupActions() {
        binding.btnPickSingle.setOnClickListener {
            pickSingle.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.btnPickMultiple.setOnClickListener {
            pickMultiple.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.btnCompress.setOnClickListener {
            if (selectedUris.isEmpty()) {
                Toast.makeText(this, "Pick an image first", Toast.LENGTH_SHORT).show()
            } else compressAll()
        }
    }

    private fun applyCtaGlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            binding.btnCompress.outlineSpotShadowColor = ContextCompat.getColor(this, R.color.acid)
            binding.btnCompress.outlineAmbientShadowColor = ContextCompat.getColor(this, R.color.acid)
        }
    }

    // ── Selection / source card ─────────────────────────────────────────────

    private fun setSelection(uris: List<Uri>) {
        selectedUris.clear()
        selectedUris.addAll(uris)

        binding.tvSelectedCount.text = "${uris.size} selected"
        val first = uris.first()
        val meta = readMeta(first)
        Glide.with(this).load(first).centerCrop().into(binding.ivThumb)
        binding.tvFname.text = when {
            uris.size > 1 -> "${meta.name}  (+${uris.size - 1} more)"
            else -> meta.name
        }
        binding.tvFsize.text = buildString {
            if (meta.dims != null) append(meta.dims).append("  ·  ")
            append(formatBytes(meta.size))
        }
        updateCompressEnabled()
    }

    private fun updateCompressEnabled() {
        val enabled = selectedUris.isNotEmpty()
        binding.btnCompress.isEnabled = enabled
        binding.btnCompress.alpha = if (enabled) 1f else 0.5f
    }

    // ── Compression ──────────────────────────────────────────────────────────

    private fun compressAll() {
        val fmt = format
        val gr = gear
        val cap = if (hardCapEnabled) binding.etHardCap.text?.toString()?.toIntOrNull() ?: 0 else 0

        setBusy(true)
        binding.resultsContainer.removeAllViews()

        lifecycleScope.launch {
            for (uri in selectedUris) {
                val originalBytes = readMeta(uri).size
                val card = ViewResultCardBinding.inflate(layoutInflater, binding.resultsContainer, false)
                binding.resultsContainer.addView(card.root)
                runCatching {
                    lateinit var out: File
                    val ms = measureTimeMillis {
                        val req = PixelDiet.with(this@MainActivity).load(uri).format(fmt).gear(gr)
                        if (cap > 0) req.hardCap(cap)
                        out = req.getFirst()
                    }
                    bindSuccess(card, uri, out, originalBytes, fmt, gearLabel(gr), ms)
                }.onFailure { e ->
                    bindFailure(card, e.message ?: e.javaClass.simpleName)
                }
            }
            setBusy(false)
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.btnCompress.isEnabled = !busy && selectedUris.isNotEmpty()
        binding.btnCompressLabel.text = if (busy) "Compressing…" else "Compress"
        binding.btnPickSingle.isEnabled = !busy
        binding.btnPickMultiple.isEnabled = !busy
    }

    private fun bindSuccess(
        card: ViewResultCardBinding, source: Uri, out: File,
        originalBytes: Long, fmt: OutputFormat, gearLbl: String, ms: Long,
    ) {
        card.tvError.visibility = View.GONE
        card.resultContent.visibility = View.VISIBLE

        val after = out.length()
        val pct = if (originalBytes > 0) (100 - after * 100 / originalBytes).toInt() else 0
        val saved = (originalBytes - after).coerceAtLeast(0)

        card.tvTime.text = "$ms ms"
        Glide.with(this).load(out).centerCrop().into(card.ivAfter)

        card.tvPct.text = if (pct >= 0) "−$pct%" else "+${-pct}%"
        card.tvFrom.text = formatBytes(originalBytes)
        card.tvFrom.paintFlags = card.tvFrom.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        card.tvTo.text = formatBytes(after)

        val afterFrac = if (originalBytes > 0) (after.toFloat() / originalBytes).coerceIn(0f, 1f) else 1f
        val afterW = (afterFrac * 1000).roundToInt().coerceIn(0, 1000)
        setWeight(card.barAfter, afterW.toFloat())
        setWeight(card.barFiller, (1000 - afterW).toFloat())

        card.tvBarOrig.text = "ORIGINAL ${formatBytes(originalBytes)}"
        card.tvBarSaved.text = formatBytes(saved)

        card.metaFormatValue.text = fmt.name
        card.metaGearValue.text = gearLbl
        card.metaFileValue.text = out.name
    }

    private fun bindFailure(card: ViewResultCardBinding, message: String) {
        card.resultContent.visibility = View.GONE
        card.tvError.visibility = View.VISIBLE
        card.tvError.text = "Failed: $message"
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private data class Meta(val name: String, val size: Long, val dims: String?)

    private fun readMeta(uri: Uri): Meta {
        var name = uri.lastPathSegment ?: "image"
        var size = -1L
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        if (size < 0) {
            size = runCatching {
                contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull() ?: -1L
        }
        val dims = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) "${opts.outWidth} × ${opts.outHeight}" else null
            }
        }.getOrNull()
        return Meta(name, size, dims)
    }

    private fun gearLabel(gear: Int): String = when (gear) {
        PixelDiet.GEAR_THIRD -> "Smart · 3rd"
        PixelDiet.GEAR_FIRST -> "Aggressive · 1st"
        else -> "Custom"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 0 -> "?"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
        else -> String.format(Locale.US, "%.2f MB", bytes / (1024f * 1024f))
    }

    private fun setWeight(view: View, weight: Float) {
        val lp = view.layoutParams as android.widget.LinearLayout.LayoutParams
        lp.weight = weight
        view.layoutParams = lp
    }

    private fun android.widget.TextView.setTextAppearanceFont(fontRes: Int) {
        typeface = androidx.core.content.res.ResourcesCompat.getFont(context, fontRes)
    }
}
