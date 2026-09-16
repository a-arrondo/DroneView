package com.aarrondo.droneview.ui.components.left.subcomps

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.aarrondo.droneview.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoButton() {
    val tooltipState = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            positioning = TooltipAnchorPosition.Above
        ),
        tooltip = {
            PlainTooltip {
                Text("DroneView by @a-arrondo\n\nThis app is only compatible with Potensic A20w drone.")
            }
        },
        state = tooltipState
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .background(
                    color = Color.White,
                    shape = CircleShape
                )
                .clickable {
                    scope.launch {
                        if (tooltipState.isVisible) {
                            tooltipState.dismiss()
                        } else {
                            tooltipState.show()
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Image(
                modifier = Modifier.size(30.dp),
                painter = painterResource(
                    if (tooltipState.isVisible) {
                        R.drawable.info_black_100
                    } else {
                        R.drawable.info_100
                    }
                ),
                contentDescription = "App Info"
            )
        }
    }
}
