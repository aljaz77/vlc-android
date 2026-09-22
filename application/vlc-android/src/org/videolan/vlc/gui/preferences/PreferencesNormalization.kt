/*
 * *************************************************************************
 *  PreferencesNormalization.kt
 * **************************************************************************
 *  Copyright © 2026 VLC authors and VideoLAN
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *  ***************************************************************************
 */

package org.videolan.vlc.gui.preferences

import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import kotlinx.coroutines.launch
import org.videolan.resources.VLCInstance
import org.videolan.resources.normalization.LoudnessTarget
import org.videolan.resources.normalization.NormalizationConfig
import org.videolan.resources.normalization.NormalizationMethod
import org.videolan.tools.KEY_NORMALIZATION_ALBUM_MODE
import org.videolan.tools.KEY_NORMALIZATION_APPLY_TO_VIDEO
import org.videolan.tools.KEY_NORMALIZATION_CUSTOM_TARGET
import org.videolan.tools.KEY_NORMALIZATION_ENABLED
import org.videolan.tools.KEY_NORMALIZATION_MAX_BOOST
import org.videolan.tools.KEY_NORMALIZATION_METHOD
import org.videolan.tools.KEY_NORMALIZATION_PEAK_LIMITER
import org.videolan.tools.KEY_NORMALIZATION_STRENGTH
import org.videolan.tools.KEY_NORMALIZATION_TARGET
import org.videolan.tools.putSingle
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.R
import org.videolan.vlc.gui.helpers.restartMediaPlayer

/**
 * Settings for volume normalization.
 *
 * Two independent choices: what loudness counts as normal, and how to get there.
 * The method determines whether a change can be applied to the running player or
 * whether libVLC has to be rebuilt first, which is why every change routes
 * through [applyChange] rather than being handled ad hoc.
 */
class PreferencesNormalization : BasePreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    /** The configuration libVLC is currently built with. */
    private var appliedConfig: NormalizationConfig? = null

    override fun getXml() = R.xml.preferences_normalization

    override fun getTitleId() = R.string.normalization_title

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        for (key in arrayOf(KEY_NORMALIZATION_CUSTOM_TARGET, KEY_NORMALIZATION_MAX_BOOST)) {
            findPreference<EditTextPreference>(key)?.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER or
                        InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        InputType.TYPE_NUMBER_FLAG_SIGNED
                it.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(6))
                it.setSelection(it.editableText.length)
            }
        }
        // Snapshot what libVLC is currently running with, so the first change can
        // tell whether it actually needs a rebuild.
        appliedConfig = NormalizationConfig.from(
            preferenceScreen.sharedPreferences ?: return
        )
        updateSummaries()
    }

    override fun onStart() {
        super.onStart()
        preferenceScreen.sharedPreferences!!.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onStop() {
        super.onStop()
        preferenceScreen.sharedPreferences!!.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (sharedPreferences == null || key == null || activity == null) return
        when (key) {
            KEY_NORMALIZATION_CUSTOM_TARGET -> {
                if (!clampDouble(sharedPreferences, key,
                        LoudnessTarget.MIN_LUFS, LoudnessTarget.MAX_LUFS,
                        LoudnessTarget.STREAMING.lufs)) return
            }
            KEY_NORMALIZATION_MAX_BOOST -> {
                if (!clampDouble(sharedPreferences, key,
                        0.0, MAX_BOOST_CEILING_DB,
                        NormalizationConfig.DEFAULT_MAX_BOOST_DB)) return
            }
            KEY_NORMALIZATION_ENABLED, KEY_NORMALIZATION_METHOD, KEY_NORMALIZATION_TARGET,
            KEY_NORMALIZATION_STRENGTH, KEY_NORMALIZATION_PEAK_LIMITER,
            KEY_NORMALIZATION_ALBUM_MODE, KEY_NORMALIZATION_APPLY_TO_VIDEO -> Unit
            else -> return
        }
        updateSummaries()
        applyChange(sharedPreferences)
    }

    /**
     * Push the new configuration out to the player.
     *
     * The device effect can always be retuned in place. The libVLC filters are
     * only read when the instance is created, so libVLC has to be rebuilt, and
     * the current track restarted, whenever those options actually change.
     * Comparing the derived option lists covers switching away from a filter
     * based method too, which would otherwise leave its filters loaded.
     */
    private fun applyChange(prefs: SharedPreferences) {
        val config = NormalizationConfig.from(prefs)
        PlaybackService.instance?.applyNormalization()

        val previous = appliedConfig
        appliedConfig = config
        if (previous != null && previous.libVlcOptions() == config.libVlcOptions()) return
        lifecycleScope.launch {
            VLCInstance.restart()
            restartMediaPlayer()
        }
    }

    /**
     * Normalise a free text number preference into range.
     *
     * @return true when the stored value was already valid. A correction writes
     * the preference again, which re-enters this listener, so the caller should
     * stand down and let the second pass do the work.
     */
    private fun clampDouble(
        prefs: SharedPreferences,
        key: String,
        min: Double,
        max: Double,
        fallback: Double
    ): Boolean {
        val stored = prefs.getString(key, null)
        val parsed = stored?.toDoubleOrNull() ?: fallback
        val clamped = parsed.coerceIn(min, max)
        val formatted = clamped.toString()
        if (formatted == stored) return true
        prefs.putSingle(key, formatted)
        findPreference<EditTextPreference>(key)?.text = formatted
        return false
    }

    private fun updateSummaries() {
        val prefs = preferenceScreen.sharedPreferences ?: return
        val config = NormalizationConfig.from(prefs)

        // The custom target only means anything when the target list is on Custom.
        findPreference<Preference>(KEY_NORMALIZATION_CUSTOM_TARGET)?.isVisible =
            config.target == LoudnessTarget.CUSTOM

        findPreference<ListPreference>(KEY_NORMALIZATION_METHOD)?.let { pref ->
            val description = getString(methodDescription(config.method))
            val timing = getString(
                if (config.method.needsLibVlcRestart) R.string.normalization_restart_warning
                else R.string.normalization_live_change
            )
            pref.summary = "$description\n$timing"
        }

        // Strength and album mode only do something for the methods that read them.
        findPreference<Preference>(KEY_NORMALIZATION_STRENGTH)?.isVisible = when (config.method) {
            NormalizationMethod.AUTO, NormalizationMethod.COMPRESSOR,
            NormalizationMethod.LEVELER, NormalizationMethod.DEVICE -> true
            else -> false
        }
        findPreference<Preference>(KEY_NORMALIZATION_ALBUM_MODE)?.isVisible =
            config.method.usesReplayGain
        findPreference<Preference>(KEY_NORMALIZATION_MAX_BOOST)?.isVisible =
            config.method.usesDeviceEffect
    }

    private fun methodDescription(method: NormalizationMethod) = when (method) {
        NormalizationMethod.AUTO -> R.string.normalization_method_auto_desc
        NormalizationMethod.REPLAYGAIN -> R.string.normalization_method_replaygain_desc
        NormalizationMethod.MEASURED -> R.string.normalization_method_measured_desc
        NormalizationMethod.COMPRESSOR -> R.string.normalization_method_compressor_desc
        NormalizationMethod.LEVELER -> R.string.normalization_method_leveler_desc
        NormalizationMethod.DEVICE -> R.string.normalization_method_device_desc
    }

    companion object {
        /** Beyond this a boost stops being normalization and starts being distortion. */
        private const val MAX_BOOST_CEILING_DB = 24.0
    }
}
