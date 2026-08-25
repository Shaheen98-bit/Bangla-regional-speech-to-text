# Kazalbrur Bangla Regional Speech-to-Text (Android)

A 100% offline, on-device Android Speech-to-Text (STT) application powered by the **Kazalbrur Conformer CTC** model (ONNX Runtime CPU) and a custom pure-Kotlin **SentencePiece Tokenizer** & **NeMo Mel Spectrogram Preprocessor**.

---

## 🌟 Key Features

- **100% Offline & Private:** Zero cloud dependencies, zero audio sent over the network.
- **Fast On-Device Inference:** Quantized INT8 Conformer CTC acoustic model running on CPU via Microsoft ONNX Runtime Mobile.
- **NeMo-Compliant Preprocessing:** Pure Kotlin DSP pipeline (Radix-2 FFT, 80-channel Slaney Mel Filterbank, per-feature normalization).
- **SentencePiece Decoding:** Custom Protobuf parser and SentencePiece tokenizer with full Bengali Unicode subword mapping and byte fallback.
- **Robust VAD & Silence Gating:** Voice Activity Detection (RMS thresholding) prevents microphone hiss / room noise from triggering false hallucinations (e.g. "আছে আছে").
- **Utterance Buffering & Accumulation:** Automatic sentence pause detection and multi-utterance transcript persistence.
- **Material Design 3 UI:** Modern Jetpack Compose interface with live audio waveform visualization, diagnostic testing dialog, and seamless file import for `.onnx` and `.model` files.

---

## 🏗️ Architecture

```
Microphone (16 kHz 16-bit Mono PCM)
        │
        ▼
AudioRecorderManager (VAD & Utterance Windowing)
        │
        ▼
MelSpectrogramPreprocessor (Hann Window -> 512 Radix-2 FFT -> 80 Slaney Mel Filterbank -> ln(mel + 1e-5))
        │
        ▼
OnnxAsrEngine (ONNX Runtime Mobile - CPU Session with 2 threads)
        │ [1, T, 129] Logprobs
        ▼
CtcDecoder (Greedy argmax -> Blank filter @ ID 128 -> Consecutive Collapse)
        │ Token IDs
        ▼
SentencePieceTokenizer (tokenizer.model Protobuf Parser -> Bengali Text)
        │
        ▼
SttViewModel & Jetpack Compose UI (Live & Accumulated Transcripts)
```

---

## 🚀 Building and Running

### Prerequisites
- Android Studio Ladybug / Meerkat or later
- JDK 17
- Android SDK 35 / 36

### Build via Gradle
```bash
# Run unit tests
./gradlew testDebugUnitTest

# Assemble debug APK
./gradlew assembleDebug
```
The output APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📦 Model & Tokenizer Setup

1. Copy or push your `kazalbrur_int8.onnx` model and `tokenizer.model` file to your Android device.
2. In the app UI, tap **Import Model** to load `kazalbrur_int8.onnx`.
3. Tap **Import Tokenizer** to load `tokenizer.model`.
4. Tap the Microphone button to begin real-time Bengali transcription.
