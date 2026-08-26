package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.ui.SttUiState
import com.example.ui.theme.ElegantDarkBackground
import com.example.ui.theme.ElegantDarkBorder
import com.example.ui.theme.ElegantDarkSurfaceCard
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.LavenderPrimary
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun ModelConfigDialog(
    uiState: SttUiState,
    onDismiss: () -> Unit,
    onImportModelClick: () -> Unit,
    onImportTokenizerClick: () -> Unit,
    onRemoveModelClick: () -> Unit,
    onRemoveTokenizerClick: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = ElegantDarkSurfaceCard),
            border = BorderStroke(1.dp, ElegantDarkBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = LavenderPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "মডেল ও কনফিগারেশন",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextMuted
                        )
                    }
                }

                HorizontalDivider(thickness = 1.dp, color = ElegantDarkBorder)

                // 1. Model Status & Actions
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ElegantDarkBackground, RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "MODEL STATUS",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                color = TextMuted
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            StatusBadge(
                                isReady = uiState.isModelImported,
                                readyText = if (uiState.modelSizeFormatted.isNotEmpty()) "Ready (${uiState.modelSizeFormatted})" else "Ready",
                                notReadyText = "Not Imported"
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Button(
                                onClick = onImportModelClick,
                                modifier = Modifier
                                    .height(38.dp)
                                    .testTag("import_model_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ElegantDarkBorder,
                                    contentColor = LavenderPrimary
                                ),
                                shape = CircleShape
                            ) {
                                Text(
                                    text = if (uiState.isModelImported) "REPLACE" else "IMPORT MODEL",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            if (uiState.isModelImported) {
                                IconButton(
                                    onClick = onRemoveModelClick,
                                    modifier = Modifier
                                        .size(34.dp)
                                        .testTag("remove_model_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Remove Model",
                                        tint = ErrorRed.copy(alpha = 0.85f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = "Conformer 120M INT8 ONNX Model (~137MB)",
                        fontSize = 11.5.sp,
                        color = TextSecondary
                    )
                }

                // 2. Tokenizer Status & Actions
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ElegantDarkBackground, RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "TOKENIZER STATUS",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                color = TextMuted
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            StatusBadge(
                                isReady = uiState.isTokenizerImported,
                                readyText = if (uiState.tokenizerVocabSize > 0) "Ready (${uiState.tokenizerVocabSize} tokens)" else "Ready",
                                notReadyText = "Not Imported"
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Button(
                                onClick = onImportTokenizerClick,
                                modifier = Modifier
                                    .height(38.dp)
                                    .testTag("import_tokenizer_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ElegantDarkBorder,
                                    contentColor = LavenderPrimary
                                ),
                                shape = CircleShape
                            ) {
                                Text(
                                    text = if (uiState.isTokenizerImported) "REPLACE" else "IMPORT TOKENIZER",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            if (uiState.isTokenizerImported) {
                                IconButton(
                                    onClick = onRemoveTokenizerClick,
                                    modifier = Modifier
                                        .size(34.dp)
                                        .testTag("remove_tokenizer_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Remove Tokenizer",
                                        tint = ErrorRed.copy(alpha = 0.85f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = "SentencePiece Unigram Tokenizer (~280KB)",
                        fontSize = 11.5.sp,
                        color = TextSecondary
                    )
                }

                // Info note
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = LavenderPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "100% On-Device Offline Inference • No Internet Required",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }
            }
        }
    }
}
