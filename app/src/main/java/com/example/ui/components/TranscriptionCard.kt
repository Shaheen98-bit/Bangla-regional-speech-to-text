package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.SttUiState
import com.example.ui.theme.ElegantDarkBackground
import com.example.ui.theme.ElegantDarkBorder
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.LavenderPrimary
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TranscriptionCard(
    uiState: SttUiState,
    onClearClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

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

    // Auto-scroll to bottom when new text arrives
    LaunchedEffect(uiState.fullTranscript, uiState.liveTranscript) {
        if (combinedText.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = ElegantDarkBackground),
        border = BorderStroke(1.dp, ElegantDarkBorder),
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
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
                            text = "LIVE LISTENING...",
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
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (combinedText.isNotEmpty()) {
                        Text(
                            text = "$wordCount words • $charCount chars",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.width(4.dp))
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
                            .size(32.dp)
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
                            .size(32.dp)
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
                            .size(32.dp)
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
                        enabled = combinedText.isNotEmpty(),
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("clear_text_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Clear Text",
                            tint = if (combinedText.isNotEmpty()) ErrorRed.copy(alpha = 0.8f) else TextMuted.copy(alpha = 0.3f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Main Large Transcription Display Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 360.dp)
                    .verticalScroll(scrollState)
            ) {
                if (combinedText.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MicOff,
                            contentDescription = null,
                            tint = TextMuted.copy(alpha = 0.4f),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (uiState.isBothReady) "মাইক্রোফোন চালু করে বাংলায় কথা বলুন..."
                            else "মডেল ও টোকেনাইজার যুক্ত করে শুরু করুন",
                            fontSize = 14.sp,
                            color = TextMuted.copy(alpha = 0.6f),
                            fontStyle = FontStyle.Italic,
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (uiState.fullTranscript.isNotEmpty()) {
                            Text(
                                text = uiState.fullTranscript,
                                fontSize = 18.sp,
                                lineHeight = 28.sp,
                                color = TextPrimary,
                                fontWeight = FontWeight.Normal,
                                modifier = Modifier.testTag("full_transcript_text")
                            )
                        }
                        if (uiState.liveTranscript.isNotEmpty()) {
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
        }
    }
}
