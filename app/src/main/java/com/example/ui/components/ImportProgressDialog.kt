package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.ui.theme.ElegantDarkBorder
import com.example.ui.theme.ElegantDarkSurfaceCard
import com.example.ui.theme.LavenderPrimary
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun ImportProgressDialog(
    fileName: String,
    progressFraction: Float,
    statusText: String
) {
    Dialog(onDismissRequest = { /* Prevent dismiss while copying */ }) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = ElegantDarkSurfaceCard),
            border = BorderStroke(1.dp, ElegantDarkBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = "ইমপোর্ট হচ্ছে... (Importing)",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = fileName,
                    fontSize = 13.sp,
                    color = LavenderPrimary
                )
                Spacer(modifier = Modifier.height(18.dp))

                if (progressFraction > 0f) {
                    LinearProgressIndicator(
                        progress = { progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = LavenderPrimary,
                        trackColor = ElegantDarkBorder
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = LavenderPrimary,
                        trackColor = ElegantDarkBorder
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = statusText.ifEmpty { "অনুগ্রহ করে অপেক্ষা করুন..." },
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
        }
    }
}
