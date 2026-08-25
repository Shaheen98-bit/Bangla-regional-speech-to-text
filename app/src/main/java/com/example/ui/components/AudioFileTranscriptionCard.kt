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
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
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
import com.example.ui.theme.ElegantDarkSurfaceCard
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
fun AudioFileTranscriptionCard(
    uiState: SttUiState,
    onSelectFileClick: () -> Unit,
    onCancelClick: () -> Unit,
    onClearClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val transcriptText = uiState.fileTranscript.trim()
    val wordCount = if (transcriptText.isEmpty()) 0 else transcriptText.split(Regex("\\s+")).size
    val charCount = transcriptText.length

    LaunchedEffect(uiState.fileTranscript) {
        if (transcriptText.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
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
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            // File Selector & Actions Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.AudioFile,
                        contentDescription = null,
                        tint = LavenderPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "AUDIO FILE TRANSCRIPTION",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = LavenderPrimary
                        )
                        if (uiState.selectedAudioFileName.isNotEmpty()) {
                            Text(
                                text = uiState.selectedAudioFileName,
                                fontSize = 12.sp,
                                color = TextSecondary,
                                maxLines = 1
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Copy
                    IconButton(
                        onClick = {
                            if (transcriptText.isNotEmpty()) {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Bangla File STT", transcriptText)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "টেক্সট ক্লিপবোর্ডে কপি হয়েছে", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = transcriptText.isNotEmpty(),
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("file_copy_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Text",
                            tint = if (transcriptText.isNotEmpty()) LavenderPrimary else TextMuted.copy(alpha = 0.3f),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Share
                    IconButton(
                        onClick = {
                            if (transcriptText.isNotEmpty()) {
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, transcriptText)
                                    type = "text/plain"
                                }
                                val shareIntent = Intent.createChooser(sendIntent, "ফাইল টেক্সট শেয়ার করুন")
                                context.startActivity(shareIntent)
                            }
                        },
                        enabled = transcriptText.isNotEmpty(),
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("file_share_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share Text",
                            tint = if (transcriptText.isNotEmpty()) LavenderPrimary else TextMuted.copy(alpha = 0.3f),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Save
                    IconButton(
                        onClick = {
                            if (transcriptText.isNotEmpty()) {
                                try {
                                    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                                    val fileName = "audio_transcript_$timeStamp.txt"
                                    val downloadsDir = context.getExternalFilesDir(null) ?: context.filesDir
                                    val file = File(downloadsDir, fileName)
                                    file.writeText(transcriptText)
                                    Toast.makeText(context, "সংরক্ষণ করা হয়েছে: ${file.name}", Toast.LENGTH_LONG).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "সংরক্ষণ ব্যর্থ: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = transcriptText.isNotEmpty(),
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("file_save_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SaveAlt,
                            contentDescription = "Save Text",
                            tint = if (transcriptText.isNotEmpty()) LavenderPrimary else TextMuted.copy(alpha = 0.3f),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Clear
                    IconButton(
                        onClick = onClearClick,
                        enabled = transcriptText.isNotEmpty() || uiState.selectedAudioFileName.isNotEmpty(),
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("file_clear_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Clear Text",
                            tint = if (transcriptText.isNotEmpty()) ErrorRed.copy(alpha = 0.8f) else TextMuted.copy(alpha = 0.3f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // File Selection / Progress Control
            if (uiState.isTranscribingFile) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ElegantDarkSurfaceCard, RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = uiState.fileTranscriptionStatus,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )

                        IconButton(
                            onClick = onCancelClick,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cancel,
                                contentDescription = "Cancel",
                                tint = ErrorRed,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    LinearProgressIndicator(
                        progress = { uiState.fileTranscriptionProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = LavenderPrimary,
                        trackColor = ElegantDarkBorder,
                        strokeCap = StrokeCap.Round
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            } else {
                Button(
                    onClick = onSelectFileClick,
                    enabled = uiState.isBothReady,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .testTag("select_audio_file_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LavenderPrimary,
                        contentColor = ElegantDarkBackground,
                        disabledContainerColor = ElegantDarkBorder.copy(alpha = 0.5f),
                        disabledContentColor = TextMuted.copy(alpha = 0.5f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.FileOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (uiState.selectedAudioFileName.isNotEmpty()) "অন্য অডিও ফাইল নির্বাচন করুন" else "অডিও ফাইল নির্বাচন করুন (MP3, WAV, M4A, OGG)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Transcription Result View
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 360.dp)
                    .verticalScroll(scrollState)
            ) {
                if (transcriptText.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AudioFile,
                            contentDescription = null,
                            tint = TextMuted.copy(alpha = 0.4f),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (uiState.isBothReady) "যেকোনো বাংলা অডিও ফাইল (MP3, WAV, M4A/AAC, OGG, FLAC) নির্বাচন করুন"
                            else "অডিও ফাইল ট্রান্সক্রিপশনের জন্য প্রথমে মডেল ও টোকেনাইজার যুক্ত করুন",
                            fontSize = 13.sp,
                            color = TextMuted.copy(alpha = 0.6f),
                            fontStyle = FontStyle.Italic,
                            textAlign = TextAlign.Center,
                            lineHeight = 19.sp
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = transcriptText,
                            fontSize = 18.sp,
                            lineHeight = 28.sp,
                            color = TextPrimary,
                            fontWeight = FontWeight.Normal,
                            modifier = Modifier.testTag("file_transcript_text")
                        )
                    }
                }
            }
        }
    }
}
