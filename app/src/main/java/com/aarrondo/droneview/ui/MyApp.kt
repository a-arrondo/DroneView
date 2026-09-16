package com.aarrondo.droneview.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import com.aarrondo.droneview.ui.components.left.DroneInfo
import com.aarrondo.droneview.ui.components.centre.CameraView
import com.aarrondo.droneview.ui.components.right.DroneControls

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aarrondo.droneview.connect.WifiMonitor

@Composable
fun MyApp(
    captureViewModel: CaptureViewModel = viewModel()
) {
    val context = LocalContext.current
    val wifiMonitor = remember { WifiMonitor(context) }
    val isConnectedToDrone by wifiMonitor.isConnectedToDrone.collectAsState()
    val droneNetwork by wifiMonitor.droneNetwork.collectAsState()

    val videoSource = captureViewModel.videoSource
    val isStreaming by videoSource.isStreaming.collectAsState()
    val isRecording by captureViewModel.isRecording.collectAsState()

    LaunchedEffect(captureViewModel) {
        captureViewModel.statusMessage.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(wifiMonitor) {
        wifiMonitor.start()
        onDispose { wifiMonitor.stop() }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val cameraWidth = maxHeight * (4f / 3f)

        val infoWidth = (maxWidth - cameraWidth) / 2.75f
        val controlsWidth = infoWidth * 1.75f

        Row(
            modifier = Modifier.fillMaxSize()
        ) {
            DroneInfo(
                modifier = Modifier
                    .width(infoWidth)
                    .fillMaxHeight()
                    .background(Color.Black),
                isConnectedToDrone = isConnectedToDrone
            )

            CameraView(
                modifier = Modifier
                    .width(cameraWidth)
                    .fillMaxHeight()
                    .background(Color.Black),
                droneNetwork = droneNetwork,
                videoSource = videoSource
            )

            DroneControls(
                modifier = Modifier
                    .width(controlsWidth)
                    .fillMaxHeight()
                    .background(Color.Black),
                isRecording = isRecording,
                isStreaming = isStreaming,
                onRecordToggle = captureViewModel::onRecordToggle,
                onSnap = captureViewModel::onSnap
            )
        }
    }
}

@Preview(
    showBackground = true,
    backgroundColor = 0xFF333333L,
    widthDp = 1200,
    heightDp = 600
)
@Composable
private fun MyAppPreview() {
    MyApp()
}
