package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.SttUiState
import com.example.ui.theme.ElegantDarkBorderSubtle
import com.example.ui.theme.ElegantDarkSurfaceCard
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.LavenderPrimary
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.TextMuted

@Composable
fun BenchmarkView(
    uiState: SttUiState,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = ElegantDarkSurfaceCard.copy(alpha = 0.5f)
        ),
        border = BorderStroke(1.dp, ElegantDarkBorderSubtle),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Row 1: INFERENCE & RTF
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "INFERENCE",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (uiState.inferenceTimeMs > 0) "${uiState.inferenceTimeMs} ms" else "-- ms",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = LavenderPrimary
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "RTF",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (uiState.rtf > 0) String.format("%.3f", uiState.rtf) else "0.000",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = if (uiState.rtf in 0.001f..0.999f) SuccessGreen else LavenderPrimary
                    )
                }
            }

            // Row 2: FEATURE SHAPE & ONNX SESSION
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "FEATURE SHAPE",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = uiState.featureShape,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = LavenderPrimary
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ONNX SESSION",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (uiState.isModelLoaded) "ACTIVE (2T)" else "NULL",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = if (uiState.isModelLoaded) SuccessGreen else ErrorRed
                    )
                }
            }
        }
    }
}
