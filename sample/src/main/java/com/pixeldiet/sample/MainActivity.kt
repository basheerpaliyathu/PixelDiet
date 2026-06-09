/*
 * Copyright 2026 Basheer
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.pixeldiet.sample

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.pixeldiet.compressor.OutputFormat
import com.pixeldiet.compressor.PixelDiet
import com.pixeldiet.sample.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import kotlin.system.measureTimeMillis

/**
 * PixelDiet feature-exercising sample app.
 *
 * Demonstrates every key feature:
 *  - Single image and batch (multi-select) input via the Android Photo Picker
 *    (content:// Uri — scoped-storage safe)
 *  - All four output formats: JPEG, WebP Lossy, WebP Lossless, PNG
 *  - All three gears: Smart (third), Aggressive (first), Custom (with max-size + max-width)
 *  - Hard cap: guarantees the output is ≤ N KB on any gear (the feature Luban 2 removed)
 *  - Before/after thumbnail, size delta, savings %, format/gear/time metadata per result
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ResultAdapter

    private val selectedUris = mutableListOf<Uri>()

    // ── Photo Picker launchers ────────────────────────────────────────────────

    private val pickSingle = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedUris.clear()
            selectedUris.add(uri)
            updateSelectionCount()
        }
    }

    private val pickMultiple = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            selectedUris.clear()
            selectedUris.addAll(uris)
            updateSelectionCount()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupRecyclerView()
        setupGearChips()
        setupHardCapToggle()
        setupClickListeners()
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        adapter = ResultAdapter()
        binding.rvResults.layoutManager = LinearLayoutManager(this)
        binding.rvResults.adapter = adapter
    }

    private fun setupGearChips() {
        binding.chipGroupGear.setOnCheckedStateChangeListener { _, _ ->
            binding.layoutCustomOptions.visibility =
                if (binding.chipGearCustom.isChecked) View.VISIBLE else View.GONE
        }
    }

    private fun setupHardCapToggle() {
        binding.cbHardCap.setOnCheckedChangeListener { _, checked ->
            binding.tilHardCap.isEnabled = checked
            binding.etHardCap.isEnabled = checked
            if (!checked) binding.etHardCap.text?.clear()
        }
    }

    private fun setupClickListeners() {
        binding.btnPickSingle.setOnClickListener {
            pickSingle.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.btnPickMultiple.setOnClickListener {
            pickMultiple.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.btnCompress.setOnClickListener {
            if (selectedUris.isEmpty()) {
                Toast.makeText(this, "Pick at least one image first", Toast.LENGTH_SHORT).show()
            } else {
                compressAll()
            }
        }
    }

    private fun updateSelectionCount() {
        binding.tvSelectedCount.text = getString(R.string.images_selected, selectedUris.size)
        binding.btnCompress.isEnabled = selectedUris.isNotEmpty()
    }

    // ── Compression ──────────────────────────────────────────────────────────

    private fun compressAll() {
        val format = selectedFormat()
        val gear = selectedGear()
        val hardCap = hardCapKb()
        val maxSize = customMaxSize()
        val maxWidth = customMaxWidth()

        setUiCompressing(true)
        adapter.submitList(emptyList())

        lifecycleScope.launch {
            val results = mutableListOf<CompressResult>()

            selectedUris.forEach { uri ->
                val originalBytes = originalSize(uri)
                val result = runCatching {
                    var outFile: java.io.File
                    val ms = measureTimeMillis {
                        val req = PixelDiet.with(this@MainActivity)
                            .load(uri)
                            .format(format)
                            .gear(gear)

                        // Apply gear-specific options
                        if (gear == PixelDiet.GEAR_CUSTOM) {
                            if (maxSize > 0) req.maxSize(maxSize)
                            if (maxWidth > 0) req.maxWidth(maxWidth)
                        }
                        // Hard cap works on any gear
                        if (hardCap > 0) req.hardCap(hardCap)

                        outFile = req.getFirst()
                    }
                    CompressResult.Success(
                        sourceUri = uri,
                        outputFile = outFile,
                        originalBytes = originalBytes,
                        compressedBytes = outFile.length(),
                        formatLabel = format.name,
                        gearLabel = gearLabel(gear),
                        durationMs = ms,
                    )
                }.getOrElse { e ->
                    CompressResult.Failure(
                        sourceUri = uri,
                        message = e.message ?: e.javaClass.simpleName,
                    )
                }
                results.add(result)
            }

            // Show results
            adapter.submitList(results.toList())
            binding.tvResultsHeader.visibility = View.VISIBLE
            setUiCompressing(false)
        }
    }

    // ── UI state ──────────────────────────────────────────────────────────────

    private fun setUiCompressing(active: Boolean) {
        binding.progressBar.visibility = if (active) View.VISIBLE else View.GONE
        binding.btnCompress.isEnabled = !active && selectedUris.isNotEmpty()
        binding.btnCompress.text = if (active) getString(R.string.btn_compressing) else getString(R.string.btn_compress)
        binding.btnPickSingle.isEnabled = !active
        binding.btnPickMultiple.isEnabled = !active
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun selectedFormat(): OutputFormat = when (binding.chipGroupFormat.checkedChipId) {
        R.id.chipJpeg         -> OutputFormat.JPEG
        R.id.chipWebpLossless -> OutputFormat.WEBP_LOSSLESS
        R.id.chipPng          -> OutputFormat.PNG
        else                   -> OutputFormat.WEBP_LOSSY
    }

    private fun selectedGear(): Int = when (binding.chipGroupGear.checkedChipId) {
        R.id.chipGearFirst  -> PixelDiet.GEAR_FIRST
        R.id.chipGearCustom -> PixelDiet.GEAR_CUSTOM
        else                 -> PixelDiet.GEAR_THIRD
    }

    private fun hardCapKb(): Int =
        if (binding.cbHardCap.isChecked)
            binding.etHardCap.text?.toString()?.toIntOrNull() ?: 0
        else 0

    private fun customMaxSize(): Int =
        binding.etMaxSize.text?.toString()?.toIntOrNull() ?: 0

    private fun customMaxWidth(): Int =
        binding.etMaxWidth.text?.toString()?.toIntOrNull() ?: 0

    private fun gearLabel(gear: Int): String = when (gear) {
        PixelDiet.GEAR_FIRST  -> "Aggressive (1st)"
        PixelDiet.GEAR_CUSTOM -> "Custom"
        else                   -> "Smart (3rd)"
    }

    private fun originalSize(uri: Uri): Long =
        runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: -1L
}
