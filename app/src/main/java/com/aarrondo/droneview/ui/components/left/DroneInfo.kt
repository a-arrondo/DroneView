package com.aarrondo.droneview.ui.components.left

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aarrondo.droneview.ui.components.left.subcomps.InfoButton
import com.aarrondo.droneview.ui.components.left.subcomps.WifiButton

@Composable
fun DroneInfo(
    modifier: Modifier = Modifier,
    isConnectedToDrone: Boolean = false
) {
    Column (
        modifier = modifier
            .fillMaxSize()
            .padding(top = 45.dp, bottom = 16.dp, start = 25.dp, end = 25.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.End
    ) {
        WifiButton(isConnected = isConnectedToDrone)

        InfoButton()
    }
}
