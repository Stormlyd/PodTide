package eu.darken.capod.common.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.capod.common.BuildConfigWrap
import eu.darken.capod.common.compose.Preview2
import eu.darken.capod.common.compose.PreviewWrapper

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsBaseItem(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconPainter: Painter? = null,
    iconTinted: Boolean = true,
    iconSize: Dp = 24.dp,
    subtitle: String? = null,
    enabled: Boolean = true,
    requiresUpgrade: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    grouped: Boolean = false,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .then(if (grouped) Modifier.fillMaxWidth() else Modifier
                .padding(horizontal = 12.dp, vertical = 3.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow))
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val contentAlpha = if (enabled) 1f else 0.5f
        val hasIcon = icon != null || iconPainter != null
        val tint = if (iconTinted) {
            MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha)
        } else {
            Color.Unspecified
        }
        if (hasIcon) {
            Box(
                modifier = (if (subtitle != null) Modifier.align(Alignment.Top) else Modifier)
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f * contentAlpha)),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(iconSize), tint = tint)
                } else if (iconPainter != null) {
                    Icon(painter = iconPainter, contentDescription = null, modifier = Modifier.size(iconSize), tint = tint)
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (hasIcon) 16.dp else 0.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (requiresUpgrade && BuildConfigWrap.FLAVOR != BuildConfigWrap.Flavor.FOSS) {
                    Spacer(modifier = Modifier.width(6.dp))
                    UpgradeBadge()
                }
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f * contentAlpha),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        trailingContent?.invoke()
    }
}

@Preview2
@Composable
private fun SettingsBaseItemPreview() = PreviewWrapper {
    SettingsBaseItem(
        title = "General Settings",
        subtitle = "Monitor mode, scanner, notifications",
        icon = Icons.TwoTone.Settings,
        onClick = {},
    )
}
