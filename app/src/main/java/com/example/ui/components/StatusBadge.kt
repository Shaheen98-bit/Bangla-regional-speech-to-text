package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.SuccessGreen

@Composable
fun StatusBadge(
    isReady: Boolean,
    readyText: String = "Ready",
    notReadyText: String = "Not Imported",
    modifier: Modifier = Modifier
) {
    val indicatorColor = if (isReady) SuccessGreen else ErrorRed
    val text = if (isReady) readyText else notReadyText

    Row(
        modifier = modifier
            .background(indicatorColor.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(indicatorColor, CircleShape)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            color = indicatorColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
