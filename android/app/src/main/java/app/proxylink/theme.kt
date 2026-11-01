package app.proxylink

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val navy = Color(0xFF0B1220)
private val navy2 = Color(0xFF111827)
private val card = Color(0xFF1B2433)
private val text = Color(0xFFE5E7EB)

private val scheme = darkColorScheme(
    primary = colok,
    onPrimary = Color(0xFF052E16),
    primaryContainer = Color(0xFF14532D),
    onPrimaryContainer = Color(0xFFBBF7D0),
    secondary = Color(0xFF86EFAC),
    onSecondary = Color(0xFF052E16),
    secondaryContainer = Color(0xFF14532D),
    onSecondaryContainer = Color(0xFFBBF7D0),
    background = navy,
    onBackground = text,
    surface = navy2,
    onSurface = text,
    surfaceVariant = card,
    onSurfaceVariant = Color(0xFF9CA3AF),
    outline = Color(0xFF374151),
    outlineVariant = Color(0xFF374151),
    error = colerr,
    surfaceContainer = navy2,
    surfaceContainerHigh = card,
    surfaceContainerHighest = card,
)

@Composable
fun proxytheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}
