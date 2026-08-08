package com.shelf.reader.reader.pageturn

import android.os.Build
import android.util.Log

/**
 * GPU Quality & Hardware Profile Manager.
 *
 * Detects GPU/SoC characteristics for optimal 3D page-curl performance and
 * prevents driver batching/Z-sorting glitches on modern Adreno architectures
 * (such as Snapdragon 8 Elite / Adreno 830 found in OnePlus 13).
 */
object GpuDeviceProfile {

    private const val TAG = "GpuDeviceProfile"

    enum class QualityTier(val stripCount: Int) {
        HIGH(42),
        MEDIUM(24),
        LOW(16)
    }

    private val isAdreno830Family: Boolean by lazy {
        val hardware = Build.HARDWARE.lowercase()
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.lowercase()
        } else {
            ""
        }
        val model = Build.MODEL.lowercase()
        val board = Build.BOARD.lowercase()

        hardware.contains("qcom") && (
            soc.contains("sm8750") ||
            soc.contains("adreno 830") ||
            model.contains("oneplus 13") ||
            model.contains("pjz110") ||
            board.contains("sun")
        ) || model.contains("oneplus 13") || board.contains("sm8750")
    }

    val currentQualityTier: QualityTier by lazy {
        val maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        when {
            isAdreno830Family -> QualityTier.HIGH
            maxMemoryMb >= 4090 -> QualityTier.HIGH
            maxMemoryMb >= 2048 -> QualityTier.MEDIUM
            else -> QualityTier.LOW
        }
    }

    fun isKnownProblematicAdrenoGpu(): Boolean {
        return isAdreno830Family
    }

    fun logGpuDiagnostics() {
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else "N/A"
        Log.i(
            TAG,
            "GPU Diagnostics -> Device: ${Build.MANUFACTURER} ${Build.MODEL}, " +
                "Hardware: ${Build.HARDWARE}, Board: ${Build.BOARD}, SoC: $soc, " +
                "IsAdreno830: $isAdreno830Family, SelectedStrips: ${currentQualityTier.stripCount}"
        )
    }
}
