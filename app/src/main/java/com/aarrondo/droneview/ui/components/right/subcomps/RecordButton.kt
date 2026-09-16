package com.aarrondo.droneview.ui.components.right.subcomps

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun RecordButton(
    modifier: Modifier = Modifier,
    isRecording: Boolean,
    enabled: Boolean = true,
    onToggle: () -> Unit
) {
    val size by animateDpAsState(
        targetValue = if (isRecording) 40.dp else 80.dp,
        label = "recordButtonSize"
    )

    val cornerRadius by animateDpAsState(
        targetValue = if (isRecording) 8.dp else 40.dp,
        label = "recordButtonCornerRadius"
    )

    Box(
        modifier = modifier
            .size(95.dp)
            .border(
                width = 4.dp,
                color = Color.White,
                shape = CircleShape
            )
            .clickable(enabled = enabled) {
                onToggle()
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .background(
                    color = Color.Red,
                    shape = RoundedCornerShape(cornerRadius)
                )
        )
    }
}

@Preview(
    showBackground = true,
    backgroundColor = 0xFF333333L
)
@Composable
private fun RecordButtonPreview() {
    RecordButton(isRecording = false, onToggle = {})
}

@Preview(
    showBackground = true,
    backgroundColor = 0xFF333333L
)
@Composable
private fun RecordButtonRecordingPreview() {
    RecordButton(isRecording = true, onToggle = {})
}

