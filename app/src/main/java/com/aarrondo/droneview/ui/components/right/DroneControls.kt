package com.aarrondo.droneview.ui.components.right

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.aarrondo.droneview.ui.components.right.subcomps.LogoContainer
import com.aarrondo.droneview.ui.components.right.subcomps.RecordButton
import com.aarrondo.droneview.ui.components.right.subcomps.SnapButton

@Composable
fun DroneControls(
    modifier: Modifier = Modifier,
    isRecording: Boolean,
    isStreaming: Boolean,
    onRecordToggle: () -> Unit,
    onSnap: () -> Unit
) {
    Column(
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            LogoContainer()
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            RecordButton(
                isRecording = isRecording,
                enabled = isStreaming,
                onToggle = onRecordToggle
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            SnapButton(
                enabled = isStreaming,
                onSnap = onSnap
            )
        }
    }
}

@Preview(
    showBackground = true,
    backgroundColor = 0xFF333333
)
@Composable
private fun DroneControlsPreview() {
    DroneControls(
        isRecording = false,
        isStreaming = true,
        onRecordToggle = {},
        onSnap = {}
    )
}
