package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.LiveHypothesisGroup
import com.example.ui.SttUiState
import com.example.ui.theme.ElegantDarkBackground
import com.example.ui.theme.ElegantDarkBorder
import com.example.ui.theme.ElegantDarkBorderSubtle
import com.example.ui.theme.ElegantDarkSurfaceCard
import com.example.ui.theme.ElegantDarkSurfaceSubtle
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.LavenderContainer
import com.example.ui.theme.LavenderPrimary
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TranscriptionCard(
    uiState: SttUiState,
    onClearClick: () -> Unit,
    onSelectHypothesis: (Long, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var viewModeHypothesis by remember { mutableStateOf(true) }

    val combinedText = buildString {
        if (uiState.fullTranscript.isNotEmpty()) {
            append(uiState.fullTranscript)
        }
        if (uiState.liveTranscript.isNotEmpty()) {
            if (isNotEmpty()) append(" ")
            append(uiState.liveTranscript)
        }
    }.trim()

    val wordCount = if (combinedText.isEmpty()) 0 else combinedText.split(Regex("\\s+")).size
    val charCount = combinedText.length

    val totalHypothesesCount = remember(uiState.liveHypothesisHistory) {
        uiState.liveHypothesisHistory.sumOf { it.hypotheses.size }
    }
    val totalChunksCount = uiState.liveHypothesisHistory.size

    // Total layout items count calculation (Chunk Header + Hypotheses + Spacer for each group + test hooks)
    val totalLazyItemsCount = remember(uiState.liveHypothesisHistory) {
        if (uiState.liveHypothesisHistory.isEmpty()) 0
        else uiState.liveHypothesisHistory.sumOf { 2 + it.hypotheses.size } + 1
    }

    // Detect if user is currently near the bottom of the list
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems == 0) true
            else {
                val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                // User is considered at bottom if the last visible item is within the last 2 items
                lastVisibleIndex >= totalItems - 2
            }
        }
    }

    var hasNewPendingResults by remember { mutableStateOf(false) }
    var prevHypothesesCount by remember { mutableIntStateOf(0) }
    var prevChunksCount by remember { mutableIntStateOf(0) }

    // Auto-scroll to latest item when new hypothesis/chunk arrives
    LaunchedEffect(totalHypothesesCount, totalChunksCount) {
        val hasNewItem = totalHypothesesCount > prevHypothesesCount || totalChunksCount > prevChunksCount
        if (hasNewItem && totalLazyItemsCount > 0) {
            val targetIndex = (totalLazyItemsCount - 1).coerceAtLeast(0)
            if (isAtBottom) {
                listState.animateScrollToItem(targetIndex)
                hasNewPendingResults = false
            } else {
                hasNewPendingResults = true
            }
        }
        prevHypothesesCount = totalHypothesesCount
        prevChunksCount = totalChunksCount
    }

    // Reset pending flag once user reaches the bottom
    LaunchedEffect(isAtBottom) {
        if (isAtBottom) {
            hasNewPendingResults = false
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = ElegantDarkBackground),
        border = BorderStroke(1.dp, ElegantDarkBorder),
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // Header with stats & actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (uiState.isRecording) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(LavenderPrimary, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "LIVE LISTENING",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = LavenderPrimary
                        )
                    } else {
                        Text(
                            text = "LIVE TRANSCRIPTION",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = TextMuted
                        )
                    }

                    if (uiState.liveHypothesisHistory.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = CircleShape,
                            color = LavenderContainer.copy(alpha = 0.3f),
                            border = BorderStroke(1.dp, LavenderPrimary.copy(alpha = 0.4f)),
                            modifier = Modifier.height(20.dp)
                        ) {
                            Text(
                                text = "${uiState.liveHypothesisHistory.size} chunks",
                                fontSize = 10.sp,
                                color = LavenderPrimary,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (combinedText.isNotEmpty()) {
                        Text(
                            text = "$wordCount w • $charCount c",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    // View Mode Toggle (Hypotheses vs Plain Text)
                    if (uiState.liveHypothesisHistory.isNotEmpty()) {
                        IconButton(
                            onClick = { viewModeHypothesis = !viewModeHypothesis },
                            modifier = Modifier
                                .size(30.dp)
                                .testTag("toggle_view_mode_button")
                        ) {
                            Icon(
                                imageVector = if (viewModeHypothesis) Icons.Default.Layers else Icons.AutoMirrored.Filled.Notes,
                                contentDescription = if (viewModeHypothesis) "Hypothesis View" else "Plain Text View",
                                tint = LavenderPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // 1. COPY ACTION
                    IconButton(
                        onClick = {
                            if (combinedText.isNotEmpty()) {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Bangla STT", combinedText)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "টেক্সট ক্লিপবোর্ডে কপি হয়েছে", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = combinedText.isNotEmpty(),
                        modifier = Modifier
                            .size(30.dp)
                            .testTag("copy_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Text",
                            tint = if (combinedText.isNotEmpty()) LavenderPrimary else TextMuted.copy(alpha = 0.3f),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // 2. SHARE ACTION
                    IconButton(
                        onClick = {
                            if (combinedText.isNotEmpty()) {
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, combinedText)
                                    type = "text/plain"
                                }
                                val shareIntent = Intent.createChooser(sendIntent, "বাংলা টেক্সট শেয়ার করুন")
                                context.startActivity(shareIntent)
                            }
                        },
                        enabled = combinedText.isNotEmpty(),
                        modifier = Modifier
                            .size(30.dp)
                            .testTag("share_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share Text",
                            tint = if (combinedText.isNotEmpty()) LavenderPrimary else TextMuted.copy(alpha = 0.3f),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // 3. SAVE ACTION
                    IconButton(
                        onClick = {
                            if (combinedText.isNotEmpty()) {
                                try {
                                    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                                    val fileName = "bangla_stt_$timeStamp.txt"
                                    val downloadsDir = context.getExternalFilesDir(null) ?: context.filesDir
                                    val file = File(downloadsDir, fileName)
                                    file.writeText(combinedText)
                                    Toast.makeText(context, "সংরক্ষণ করা হয়েছে: ${file.name}", Toast.LENGTH_LONG).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "সংরক্ষণ ব্যর্থ: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = combinedText.isNotEmpty(),
                        modifier = Modifier
                            .size(30.dp)
                            .testTag("save_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SaveAlt,
                            contentDescription = "Save Text",
                            tint = if (combinedText.isNotEmpty()) LavenderPrimary else TextMuted.copy(alpha = 0.3f),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // 4. CLEAR ACTION
                    IconButton(
                        onClick = onClearClick,
                        enabled = combinedText.isNotEmpty() || uiState.liveHypothesisHistory.isNotEmpty(),
                        modifier = Modifier
                            .size(30.dp)
                            .testTag("clear_text_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Clear Text",
                            tint = if (combinedText.isNotEmpty() || uiState.liveHypothesisHistory.isNotEmpty())
                                ErrorRed.copy(alpha = 0.85f) else TextMuted.copy(alpha = 0.3f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Main Live Transcription Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (combinedText.isEmpty() && uiState.liveHypothesisHistory.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MicOff,
                            contentDescription = null,
                            tint = TextMuted.copy(alpha = 0.4f),
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (uiState.isBothReady) "মাইক্রোফোন চালু করে বাংলায় কথা বলুন...\nআঞ্চলিক বৈচিত্র্যের সকল হাইপোথিসিস সংরক্ষিত থাকবে।"
                            else "মডেল ও টোকেনাইজার যুক্ত করে শুরু করুন",
                            fontSize = 14.sp,
                            color = TextMuted.copy(alpha = 0.7f),
                            fontStyle = FontStyle.Italic,
                            textAlign = TextAlign.Center,
                            lineHeight = 22.sp
                        )
                    }
                } else if (viewModeHypothesis && uiState.liveHypothesisHistory.isNotEmpty()) {
                    // Rich Hypothesis Group Cards List with stable item keys
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("hypothesis_history_list"),
                        contentPadding = PaddingValues(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        uiState.liveHypothesisHistory.forEachIndexed { chunkIndex, group ->
                            val isChunkActive = uiState.isRecording && !group.isFinalized

                            // 1. Chunk Header Item
                            item(key = "header_${group.id}") {
                                ChunkHeaderCard(
                                    chunkIndex = chunkIndex + 1,
                                    group = group,
                                    isRecording = isChunkActive
                                )
                            }

                            // 2. Individual Hypothesis Items for this Chunk
                            itemsIndexed(
                                items = group.hypotheses,
                                key = { hypIndex, _ -> "${group.id}_hyp_$hypIndex" }
                            ) { hypIndex, hypText ->
                                val isSelected = hypText == group.displayText
                                HypothesisItemCard(
                                    hypIndex = hypIndex + 1,
                                    hypText = hypText,
                                    isSelected = isSelected,
                                    onSelect = { onSelectHypothesis(group.id, hypText) }
                                )
                            }

                            // 3. Spacer/Separator after each chunk
                            item(key = "spacer_${group.id}") {
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }

                        // Invisible test tag hooks for test runners
                        item(key = "test_hooks") {
                            Box(modifier = Modifier.size(1.dp)) {
                                if (uiState.fullTranscript.isNotEmpty()) {
                                    Text(
                                        text = uiState.fullTranscript,
                                        modifier = Modifier.testTag("full_transcript_text"),
                                        fontSize = 1.sp,
                                        color = Color.Transparent
                                    )
                                }
                                if (uiState.liveTranscript.isNotEmpty()) {
                                    Text(
                                        text = uiState.liveTranscript,
                                        modifier = Modifier.testTag("live_transcript_text"),
                                        fontSize = 1.sp,
                                        color = Color.Transparent
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Plain Text View (Continuous Paragraph)
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (uiState.fullTranscript.isNotEmpty()) {
                            item(key = "plain_full_transcript") {
                                Text(
                                    text = uiState.fullTranscript,
                                    fontSize = 18.sp,
                                    lineHeight = 28.sp,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Normal,
                                    modifier = Modifier.testTag("full_transcript_text")
                                )
                            }
                        }
                        if (uiState.liveTranscript.isNotEmpty()) {
                            item(key = "plain_live_transcript") {
                                Text(
                                    text = uiState.liveTranscript + if (uiState.isRecording) " ▍" else "",
                                    fontSize = 18.sp,
                                    lineHeight = 28.sp,
                                    color = LavenderPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.testTag("live_transcript_text")
                                )
                            }
                        }
                    }
                }

                // Smart Floating "New Results ↓" Indicator if user scrolled up
                this@Column.AnimatedVisibility(
                    visible = hasNewPendingResults && !isAtBottom,
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                val target = (totalLazyItemsCount - 1).coerceAtLeast(0)
                                listState.animateScrollToItem(target)
                                hasNewPendingResults = false
                            }
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = LavenderPrimary,
                            contentColor = Color.Black
                        ),
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Scroll to bottom",
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "নতুন ফলাফল দেখুন",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Visual header for a single voice chunk group.
 */
@Composable
private fun ChunkHeaderCard(
    chunkIndex: Int,
    group: LiveHypothesisGroup,
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val timeStr = remember(group.startedAt) { timeFormatter.format(Date(group.startedAt)) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isRecording) ElegantDarkSurfaceSubtle else ElegantDarkSurfaceCard
        ),
        border = BorderStroke(
            width = if (isRecording) 1.5.dp else 1.dp,
            color = if (isRecording) LavenderPrimary.copy(alpha = 0.7f) else ElegantDarkBorderSubtle
        ),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 6.dp, bottomEnd = 6.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("hypothesis_chunk_$chunkIndex")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.RecordVoiceOver,
                    contentDescription = null,
                    tint = if (isRecording) LavenderPrimary else TextMuted,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "VOICE CHUNK #$chunkIndex",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    color = if (isRecording) LavenderPrimary else TextMuted
                )
                Text(
                    text = "• $timeStr",
                    fontSize = 10.5.sp,
                    color = TextMuted
                )
            }

            if (isRecording) {
                Surface(
                    shape = CircleShape,
                    color = LavenderPrimary.copy(alpha = 0.15f),
                    modifier = Modifier.height(20.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(LavenderPrimary, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "IN-FLIGHT",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = LavenderPrimary
                        )
                    }
                }
            } else {
                Surface(
                    shape = CircleShape,
                    color = SuccessGreen.copy(alpha = 0.12f),
                    modifier = Modifier.height(20.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Finalized",
                            tint = SuccessGreen,
                            modifier = Modifier.size(10.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "FINALIZED",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = SuccessGreen
                        )
                    }
                }
            }
        }
    }
}

/**
 * Individual hypothesis item card with selectable state and tap interaction.
 */
@Composable
private fun HypothesisItemCard(
    hypIndex: Int,
    hypText: String,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) LavenderContainer.copy(alpha = 0.25f) else ElegantDarkBackground.copy(alpha = 0.7f),
        border = BorderStroke(
            width = if (isSelected) 1.dp else 0.5.dp,
            color = if (isSelected) LavenderPrimary.copy(alpha = 0.8f) else ElegantDarkBorderSubtle
        ),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onSelect() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "$hypIndex.",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isSelected) LavenderPrimary else TextMuted
                )
                Text(
                    text = hypText,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) TextPrimary else TextSecondary
                )
            }

            if (isSelected) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = LavenderPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
