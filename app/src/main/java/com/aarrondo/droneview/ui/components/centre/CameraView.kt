package com.aarrondo.droneview.ui.components.centre

import android.net.Network
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aarrondo.droneview.connect.IcommConsts
import com.aarrondo.droneview.connect.UdpVideoSource

@Composable
fun CameraView(
    modifier: Modifier = Modifier,
    droneNetwork: Network? = null,
    videoSource: UdpVideoSource
) {
    val inspection = LocalInspectionMode.current
    val frame by videoSource.frame.collectAsState()
    val isStreaming by videoSource.isStreaming.collectAsState()
    val framesReceived by videoSource.framesReceived.collectAsState()

    LaunchedEffect(videoSource, droneNetwork) {
        if (!inspection) videoSource.restart(droneNetwork)
    }
    DisposableEffect(videoSource) {
        onDispose { videoSource.stop() }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        val bmp = frame
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Drone live video",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            if (isStreaming) {
                Text(
                    text = "● LIVE  $framesReceived frames",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                )
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
            ) {
                if (!inspection) CircularProgressIndicator(color = Color.White)
                Text(
                    text = if (droneNetwork == null) {
                        "Connect to drone WiFi:\n${IcommConsts.DRONE_SSID}"
                    } else {
                        "Waiting for video…"
                    },
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000L, widthDp = 640, heightDp = 480)
@Composable
private fun CameraViewPreview() {
    val previewSource = remember { UdpVideoSource() }
    CameraView(videoSource = previewSource)
}

