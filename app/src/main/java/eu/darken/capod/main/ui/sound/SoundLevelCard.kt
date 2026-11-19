package eu.darken.capod.main.ui.sound

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import eu.darken.capod.R
import eu.darken.capod.main.core.sound.SoundLevelMeter
import eu.darken.capod.main.core.sound.SoundLevelReading
import kotlin.math.roundToInt

@Composable
fun SoundLevelCard(
    reading: SoundLevelReading?,
    isMeasuring: Boolean,
    error: String?,
    calibration: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCalibrationChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        if (result) onStart()
    }

    val db = reading?.db

    // Never keep the microphone running once the app is no longer visible.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) onStop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.TwoTone.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.sound_level_subtitle),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.sound_level_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))

            if (!granted) {
                Text(
                    text = stringResource(R.string.sound_level_permission_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) }) {
                    Text(stringResource(R.string.sound_level_grant))
                }
                return@Column
            }

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = db?.roundToInt()?.toString() ?: "--",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = " dB",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Spacer(Modifier.weight(1f))
                reading?.let {
                    Text(
                        text = stringResource(bandRes(it)),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            LinearProgressIndicator(
                progress = { ((db ?: 0f) / 120f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isMeasuring) {
                    OutlinedButton(onClick = onStop) {
                        Text(stringResource(R.string.sound_level_stop))
                    }
                } else {
                    Button(onClick = onStart) {
                        Text(stringResource(R.string.sound_level_start))
                    }
                }
            }
            error?.let { message ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.sound_level_error, message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.sound_level_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.sound_level_calibration_label, calibration),
                style = MaterialTheme.typography.labelLarge,
            )
            Slider(
                value = calibration.toFloat(),
                onValueChange = { onCalibrationChange(it.roundToInt()) },
                valueRange = SoundLevelMeter.MIN_CALIBRATION_DB.toFloat()..
                    SoundLevelMeter.MAX_CALIBRATION_DB.toFloat(),
                steps = (SoundLevelMeter.MAX_CALIBRATION_DB - SoundLevelMeter.MIN_CALIBRATION_DB) - 1,
            )
            Text(
                text = stringResource(R.string.sound_level_calibration_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun bandRes(reading: SoundLevelReading): Int = when (reading.band) {
    SoundLevelReading.Band.QUIET -> R.string.sound_level_band_quiet
    SoundLevelReading.Band.NORMAL -> R.string.sound_level_band_normal
    SoundLevelReading.Band.LOUD -> R.string.sound_level_band_loud
    SoundLevelReading.Band.VERY_LOUD -> R.string.sound_level_band_very_loud
}