package eu.darken.capod.main.ui.overview.cards.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.darken.capod.R
import kotlin.math.roundToInt

/**
 * Stepless Adaptive Audio bias, matching the continuous control Apple added to the system
 * listening-mode UI. 0 favours transparency/off, 100 favours maximum noise reduction.
 */
@Composable
fun AdaptiveNoiseControl(
    level: Int,
    enabled: Boolean,
    onLevelChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember(level) { mutableIntStateOf(level.coerceIn(0, 100)) }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.adaptive_noise_strength_label),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${draft}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = draft.toFloat(),
            onValueChange = { draft = it.roundToInt() },
            onValueChangeFinished = { onLevelChange(draft) },
            valueRange = 0f..100f,
            steps = 99,
            enabled = enabled,
        )
        Text(
            text = stringResource(R.string.adaptive_noise_strength_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
    }
}