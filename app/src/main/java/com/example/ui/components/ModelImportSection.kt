package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.SttUiState
import com.example.ui.theme.ElegantDarkBorder
import com.example.ui.theme.ElegantDarkSurfaceCard
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.LavenderPrimary
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun ModelImportSection(
    uiState: SttUiState,
    onImportModelClick: () -> Unit,
    onImportTokenizerClick: () -> Unit,
    onRemoveModelClick: () -> Unit,
    onRemoveTokenizerClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Status & Import Card (Unified Elegant Dark Card)
        Card(
            colors = CardDefaults.cardColors(containerColor = ElegantDarkSurfaceCard),
            border = BorderStroke(1.dp, ElegantDarkBorder),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Model Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "MODEL STATUS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusBadge(
                                isReady = uiState.isModelImported,
                                readyText = if (uiState.modelSizeFormatted.isNotEmpty()) "Ready (${uiState.modelSizeFormatted})" else "Ready",
                                notReadyText = "Not Imported"
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Button(
                            onClick = onImportModelClick,
                            modifier = Modifier
                                .height(42.dp)
                                .testTag("import_model_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElegantDarkBorder,
                                contentColor = LavenderPrimary
                            ),
                            shape = CircleShape
                        ) {
                            Text(
                                text = if (uiState.isModelImported) "REPLACE MODEL" else "IMPORT MODEL",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        if (uiState.isModelImported) {
                            IconButton(
                                onClick = onRemoveModelClick,
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("remove_model_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Remove Model",
                                    tint = ErrorRed.copy(alpha = 0.8f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(
                    thickness = 1.dp,
                    color = ElegantDarkBorder
                )

                // Tokenizer Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "TOKENIZER STATUS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusBadge(
                                isReady = uiState.isTokenizerImported,
                                readyText = if (uiState.tokenizerVocabSize > 0) "Ready (${uiState.tokenizerVocabSize} tokens)" else "Ready",
                                notReadyText = "Not Imported"
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Button(
                            onClick = onImportTokenizerClick,
                            modifier = Modifier
                                .height(42.dp)
                                .testTag("import_tokenizer_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElegantDarkBorder,
                                contentColor = LavenderPrimary
                            ),
                            shape = CircleShape
                        ) {
                            Text(
                                text = if (uiState.isTokenizerImported) "REPLACE TOKENIZER" else "IMPORT TOKENIZER",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        if (uiState.isTokenizerImported) {
                            IconButton(
                                onClick = onRemoveTokenizerClick,
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("remove_tokenizer_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Remove Tokenizer",
                                    tint = ErrorRed.copy(alpha = 0.8f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Storage & File Information Card
        Card(
            colors = CardDefaults.cardColors(containerColor = ElegantDarkSurfaceCard.copy(alpha = 0.6f)),
            border = BorderStroke(1.dp, ElegantDarkBorder.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Info",
                    tint = LavenderPrimary,
                    modifier = Modifier
                        .size(18.dp)
                        .padding(top = 2.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "Device Storage Files (SAF)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "• Model: kazalbrur_int8.onnx (~137 MB)\n• Tokenizer: tokenizer.model (~280 KB)\n• Location: Stored in app internal filesDir/models/\n• Note: On real Android device/APK, the SAF document picker directly selects files from your device storage.",
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}
