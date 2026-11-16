package app.konspektor.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

val KonspektorShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * App theme for the Compose screens: always dark, Material 3,
 * palette from material-color-utilities, Inter typography.
 */
@Composable
fun KonspektorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KonspektorDarkColors,
        typography = KonspektorTypography,
        shapes = KonspektorShapes,
        content = content,
    )
}
