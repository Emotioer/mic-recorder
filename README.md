# مسجل الصوت — Mic Recorder

مسجل صوت بسيط لأندرويد، معمول للموبايلات اللي المايك الأساسي فيها بايظ: بيلقط الصوت من القناة الشغالة تلقائيًا وبيحفظ ملفات m4a صغيرة الحجم.

A simple voice recorder for Android 7+ built for phones with a broken main microphone: it records stereo 48 kHz audio, automatically picks the mic channel that actually carries your voice, and saves small AAC `.m4a` files. Dark UI, one record button, no clutter.

## Features — المميزات

- 🎤 Auto-picks the live mic channel by comparing left/right energy — بيختار القناة الشغالة في المايك تلقائيًا
- 📦 Small files: AAC-LC 64 kbps `.m4a`, about 8 KB per second — ملفات مضغوطة وصغيرة الحجم
- ⚡ Real-time encoding while recording, so saving is near-instant — الترميز بيحصل أثناء التسجيل، فالحفظ بيتم في أقل من ثانية
- 🧹 Adaptive noise gate cleans up background noise live — تنقية الصوت من الضوضاء أثناء التسجيل
- 📊 Live waveform while recording — شكل موجي متحرك أثناء التسجيل
- 🔉 Consistent comfortable loudness (0.4 gain) — مستوى صوت ثابت وهادي
- ▶️ Built-in playback — سماع التسجيل من جوه التطبيق
- 📤 Share to WhatsApp or any app — مشاركة على واتساب أو أي تطبيق تاني
- 🗑️ One-tap delete — مسح التسجيل بضغطة واحدة
- 🖤 Dark theme, Arabic interface — ثيم غامق وواجهة بالعربي
- 🔒 One permission only: `RECORD_AUDIO`. No ads, no tracking, no accounts

## How it works — إزاي شغال

- `AudioRecord` captures 48 kHz stereo PCM16 from the `CAMCORDER` audio source, falling back to `UNPROCESSED` then `DEFAULT`. On phones with a dead primary mic, the camcorder mic path often still works — and frequently only one stereo channel carries any signal, which is why the app measures both and locks onto the live one.
- After the first second, the app locks onto the louder channel, applies 0.4 gain, and converts to mono.
- A subtle adaptive noise gate runs live: the noise floor is estimated from the quietest 10% of 20 ms windows, and windows below 2x the floor are faded toward silence with per-sample gain ramping so there are no clicks.
- The mono PCM is encoded to AAC-LC in an `.m4a` container with `MediaCodec` + `MediaMuxer` as the audio arrives, so pressing stop only has to flush the encoder tail — saving takes well under a second.
- Files are saved in the app-private external folder and shared through an internal `ContentProvider`, so `RECORD_AUDIO` stays the only permission the app needs.

## Usage — طريقة الاستخدام

1. Tap the red «سجل» button to start recording
2. Tap «إيقاف» to stop, then wait for the "اتحفظ التسجيل" message
3. «اسمع» plays it back, «ابعت» shares it, «احذف» deletes it

## Build — البناء

No Android Studio needed. `build.sh` drives `aapt2`, `javac`, `d8` and `apksigner` directly. You need JDK 11+, Android build-tools 30.0.3 and the android-28 platform:

```bash
export BT=/path/to/build-tools/30.0.3
export PLATFORM=/path/to/platforms/android-28/android.jar
export KEYSTORE=/path/to/keystore.jks KS_PASS=yourpassword
./build.sh
```

The launcher icons are generated programmatically — no image assets needed:

```bash
python3 gen_icon.py
```

## Requirements — المتطلبات

- Android 7.0 (API 24) or newer
- Microphone permission
