package com.aarrondo.droneview.ui.components.right.subcomps

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import com.aarrondo.droneview.R

@Composable
fun LogoContainer() {
    Image(
        painter = painterResource(R.drawable.logo_image),
        contentDescription = null
    )
}

@Preview(showBackground = true)
@Composable
fun LogoContainerPreview() {
    LogoContainer()
}
