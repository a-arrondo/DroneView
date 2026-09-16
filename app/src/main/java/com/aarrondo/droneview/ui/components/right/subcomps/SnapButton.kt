package com.aarrondo.droneview.ui.components.right.subcomps

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun SnapButton(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSnap: () -> Unit
) {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .scale(scale.value)
            .size(65.dp)
            .background(
                color = Color.White,
                shape = CircleShape
            )
            .clickable(enabled = enabled) {
                scope.launch {
                    scale.animateTo(0.9f)
                    scale.animateTo(1f)
                    onSnap()
                }
            }
    )
}

@Preview(
    showBackground = true,
    backgroundColor = 0xFF333333L
)
@Composable
private fun SnapButtonPreview() {
    SnapButton(onSnap = {})
}

