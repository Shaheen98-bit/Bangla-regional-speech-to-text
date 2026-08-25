package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.components.AudioFileTranscriptionCard
import com.example.ui.components.BenchmarkView
import com.example.ui.components.DiagnosticDialog
import com.example.ui.components.ImportProgressDialog
import com.example.ui.components.ModelImportSection
import com.example.ui.components.TranscriptionCard
import com.example.ui.components.WaveformView
import com.example.ui.theme.ElegantDarkBackground
import com.example.ui.theme.ElegantDarkBorder
import com.example.ui.theme.ElegantDarkSurfaceCard
import com.example.ui.theme.ErrorContainer
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.LavenderPrimary
import com.example.ui.theme.PurpleDarkCore
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SttScreen(
    viewModel: SttViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // SAF Picker for Model (.onnx)
    val modelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importModel(it) }
    }

    // SAF Picker for Tokenizer (.model)
    val tokenizerPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importTokenizer(it) }
    }

    // SAF Picker for Audio File (MP3, WAV, M4A, AAC, OGG, FLAC, MP4, 3GP)
    val audioFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.transcribeAudioFile(it) }
    }

    // Audio Permission Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startRecording()
        } else {
            Toast.makeText(context, "Microphone permission is required for speech recognition", Toast.LENGTH_LONG).show()
        }
    }

    // Pulse animation for recording state
    val infiniteTransition = rememberInfiniteTransition(label = "pulseTransition")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // Display user messages via Snackbar
    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissUserMessage()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        containerColor = ElegantDarkBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "বাংলা স্পিচ টু টেক্সট",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 21.sp,
                            letterSpacing = (-0.5).sp,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Kazalbrur Conformer 120M • 100% Offline",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextSecondary
                        )
                    }
                },
                actions = {
                    // Offline badge
                    Row(
                        modifier = Modifier
                            .background(LavenderPrimary.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = "100% Offline",
                            tint = LavenderPrimary,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "OFFLINE",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = LavenderPrimary
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Diagnostic test button
                    IconButton(
                        onClick = { viewModel.runDiagnosticTest() },
                        modifier = Modifier.testTag("diagnostic_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Assessment,
                            contentDescription = "Run Diagnostics",
                            tint = TextMuted
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ElegantDarkBackground
                )
            )
        },
        bottomBar = {
            if (uiState.selectedTab == 0) {
                // Bottom Live Recording Controls
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    ElegantDarkBackground.copy(alpha = 0.9f),
                                    ElegantDarkBackground
                                )
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left: CLEAR Button
                        Button(
                            onClick = { viewModel.clearTranscript() },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .testTag("clear_button"),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElegantDarkBorder,
                                contentColor = TextPrimary
                            )
                        ) {
                            Text(
                                text = "CLEAR",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 0.5.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        // Center: Large Circular Action Button
                        val isActionEnabled = uiState.isBothReady
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .scale(if (uiState.isRecording) pulseScale else 1f)
                                .clip(CircleShape)
                                .background(
                                    if (isActionEnabled) {
                                        if (uiState.isRecording) ErrorContainer else LavenderPrimary
                                    } else {
                                        LavenderPrimary.copy(alpha = 0.35f)
                                    }
                                )
                                .clickable(enabled = isActionEnabled) {
                                    if (!uiState.isRecording) {
                                        val permission = Manifest.permission.RECORD_AUDIO
                                        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                                            viewModel.startRecording()
                                        } else {
                                            permissionLauncher.launch(permission)
                                        }
                                    } else {
                                        viewModel.stopRecording()
                                    }
                                }
                                .testTag(if (uiState.isRecording) "stop_recording_button" else "start_recording_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            // Inner Dark Circle
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (uiState.isRecording) Color(0xFF601410) else PurpleDarkCore
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (uiState.isRecording) {
                                    Icon(
                                        imageVector = Icons.Default.Stop,
                                        contentDescription = "Stop Recording",
                                        tint = ErrorRed,
                                        modifier = Modifier.size(17.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = "Start Recording",
                                        tint = LavenderPrimary,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        // Right: STOP / START Pill Button
                        Button(
                            onClick = {
                                if (uiState.isRecording) {
                                    viewModel.stopRecording()
                                } else if (uiState.isBothReady) {
                                    val permission = Manifest.permission.RECORD_AUDIO
                                    if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                                        viewModel.startRecording()
                                    } else {
                                        permissionLauncher.launch(permission)
                                    }
                                }
                            },
                            enabled = uiState.isBothReady,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .testTag("secondary_action_button"),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (uiState.isRecording) ErrorContainer else ElegantDarkBorder,
                                contentColor = if (uiState.isRecording) Color.White else TextPrimary,
                                disabledContainerColor = ElegantDarkBorder.copy(alpha = 0.5f),
                                disabledContentColor = TextMuted.copy(alpha = 0.5f)
                            )
                        ) {
                            Text(
                                text = if (uiState.isRecording) "STOP" else "RECORD",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Compact Collapsed/Expandable Model and Tokenizer Section
            ModelImportSection(
                uiState = uiState,
                onToggleCollapse = { viewModel.toggleConfigCollapsed() },
                onImportModelClick = {
                    try {
                        modelPickerLauncher.launch(arrayOf("*/*"))
                    } catch (e: Exception) {
                        viewModel.showUserMessage("Import model unavailable in preview. Please install the APK on a real Android device.")
                    }
                },
                onImportTokenizerClick = {
                    try {
                        tokenizerPickerLauncher.launch(arrayOf("*/*"))
                    } catch (e: Exception) {
                        viewModel.showUserMessage("Import tokenizer unavailable in preview. Please install the APK on a real Android device.")
                    }
                },
                onRemoveModelClick = { viewModel.removeModel() },
                onRemoveTokenizerClick = { viewModel.removeTokenizer() }
            )

            // 2. Navigation Tabs for Live Transcription vs Audio File Transcription
            TabRow(
                selectedTabIndex = uiState.selectedTab,
                containerColor = ElegantDarkSurfaceCard,
                contentColor = LavenderPrimary,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[uiState.selectedTab]),
                        color = LavenderPrimary
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
            ) {
                Tab(
                    selected = uiState.selectedTab == 0,
                    onClick = { viewModel.setSelectedTab(0) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("লাইভ স্পিচ", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                )
                Tab(
                    selected = uiState.selectedTab == 1,
                    onClick = { viewModel.setSelectedTab(1) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.AudioFile, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("অডিও ফাইল", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                )
            }

            // 3. Tab Content
            if (uiState.selectedTab == 0) {
                // Live Audio Waveform Visualizer
                WaveformView(
                    isRecording = uiState.isRecording,
                    rmsLevel = uiState.rmsLevel
                )

                // Large Live Transcription Display Card with Copy, Share, Save, Clear
                TranscriptionCard(
                    uiState = uiState,
                    onClearClick = { viewModel.clearTranscript() }
                )
            } else {
                // Audio File Transcription Card with File Picker, Progress, Copy, Share, Save, Clear
                AudioFileTranscriptionCard(
                    uiState = uiState,
                    onSelectFileClick = {
                        try {
                            audioFilePickerLauncher.launch(
                                arrayOf(
                                    "audio/*",
                                    "video/mp4",
                                    "video/3gpp",
                                    "application/ogg"
                                )
                            )
                        } catch (e: Exception) {
                            viewModel.showUserMessage("File picker error: ${e.localizedMessage}")
                        }
                    },
                    onCancelClick = { viewModel.cancelFileTranscription() },
                    onClearClick = { viewModel.clearFileTranscript() }
                )
            }

            // Benchmark & Performance Telemetry
            BenchmarkView(uiState = uiState)

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Import Streaming Dialog
        if (uiState.isImporting) {
            ImportProgressDialog(
                fileName = uiState.importFileName,
                progressFraction = uiState.importProgressFraction,
                statusText = uiState.importStatusText
            )
        }

        // Diagnostic / Test Mode Modal Dialog
        if (uiState.showDiagnosticDialog) {
            DiagnosticDialog(
                uiState = uiState,
                onDismiss = { viewModel.dismissDiagnosticDialog() },
                onRerunTest = { viewModel.runDiagnosticTest() }
            )
        }
    }
}
