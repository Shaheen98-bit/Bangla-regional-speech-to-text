package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun ModelImportSection(
    uiState: SttUiState,
    onToggleCollapse: () -> Unit,
    onImportModelClick: () -> Unit,
    onImportTokenizerClick: () -> Unit,
    onRemoveModelClick: () -> Unit,
    onRemoveTokenizerClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = ElegantDarkSurfaceCard),
        border = BorderStroke(1.dp, ElegantDarkBorder),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Compact Header (Always Visible)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleCollapse() }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Configuration",
                        tint = LavenderPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "মডেল ও কনফিগারেশন",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (uiState.isModelImported) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (uiState.isModelImported) SuccessGreen else TextMuted,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = if (uiState.isModelImported) "মডেল প্রস্তুত" else "মডেল নেই",
                                    fontSize = 11.sp,
                                    color = if (uiState.isModelImported) SuccessGreen else TextMuted
                                )
                            }

                            Text(text = "•", fontSize = 11.sp, color = TextMuted)

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (uiState.isTokenizerImported) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (uiState.isTokenizerImported) SuccessGreen else TextMuted,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = if (uiState.isTokenizerImported) "টোকেনাইজার প্রস্তুত" else "টোকেনাইজার নেই",
                                    fontSize = 11.sp,
                                    color = if (uiState.isTokenizerImported) SuccessGreen else TextMuted
                                )
                            }
                        }
                    }
                }

                IconButton(
                    onClick = onToggleCollapse,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("toggle_config_button")
                ) {
                    Icon(
                        imageVector = if (uiState.isConfigCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                        contentDescription = if (uiState.isConfigCollapsed) "Expand" else "Collapse",
                        tint = TextMuted
                    )
                }
            }

            // Expandable Content
            AnimatedVisibility(
                visible = !uiState.isConfigCollapsed,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HorizontalDivider(thickness = 1.dp, color = ElegantDarkBorder)

                    // Model Row
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
                            Spacer(modifier = Modifier.height(2.dp))
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
                                        tint = ErrorRed.copy(alpha = 0.8f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(thickness = 1.dp, color = ElegantDarkBorder)

                    // Tokenizer Row
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
                            Spacer(modifier = Modifier.height(2.dp))
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
                                        tint = ErrorRed.copy(alpha = 0.8f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Storage Hint
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Info",
                            tint = LavenderPrimary,
                            modifier = Modifier
                                .size(15.dp)
                                .padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Model: kazalbrur_int8.onnx (~137MB) • Tokenizer: tokenizer.model (~280KB)",
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}
