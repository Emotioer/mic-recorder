package com.instinct.micfix;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int SAMPLE_RATE = 48000;
    private static final int REQ_PERM = 7;
    private static final int MAX_SECONDS = 180;
    private static final float GAIN = 0.4f;
    private static final int GATE_WIN = SAMPLE_RATE / 50; // 20 ms
    private static final int GATE_WARMUP_WINDOWS = 50;    // ~1 s before gating kicks in

    private TextView status;
    private WaveformView wave;
    private Button btnRec, btnPlay, btnShare, btnDel;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private volatile boolean recording = false;
    private volatile boolean saving = false;
    private Thread capThread;
    private File lastFile;
    private MediaPlayer player;

    // --- recording-session state, touched only by the capture thread ---
    private MediaCodec enc;
    private MediaMuxer muxer;
    private boolean muxerStarted;
    private int muxTrack;
    private final MediaCodec.BufferInfo bufInfo = new MediaCodec.BufferInfo();
    private long ptsUs;
    private File outFile;
    private int channel = -1;      // -1 = not decided yet
    private long sumL, sumR;
    private long totalMonoSamples;
    private int sessionPeak;
    private final int[] rmsHist = new int[700]; // window-RMS histogram, 50-unit buckets
    private long winCount;
    private float gatePrevGain = 1f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_PERM);
        }
    }

    @Override
    protected void onDestroy() {
        recording = false;
        stopPlayer();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_PERM) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                status.setText("جاهز");
            } else {
                status.setText("محتاج إذن المايك عشان يسجل");
            }
        }
    }

    private int dp(int d) {
        return (int) (getResources().getDisplayMetrics().density * d + 0.5f);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setBackgroundColor(Color.parseColor("#121212"));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#E0E0E0"));
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        status.setText("جاهز");
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        wave = new WaveformView(this);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-1, dp(90));
        wp.setMargins(0, dp(20), 0, 0);
        root.addView(wave, wp);

        btnRec = new Button(this);
        btnRec.setText("● سجل");
        btnRec.setTextColor(Color.WHITE);
        btnRec.setTextSize(20);
        GradientDrawable red = new GradientDrawable();
        red.setColor(Color.parseColor("#D32F2F"));
        red.setCornerRadius(dp(14));
        btnRec.setBackground(red);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(72));
        rp.setMargins(0, dp(16), 0, dp(16));
        root.addView(btnRec, rp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        root.addView(row, new LinearLayout.LayoutParams(-1, -2));

        btnPlay = actionButton("اسمع");
        btnShare = actionButton("ابعت");
        btnDel = actionButton("احذف");
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(56), 1f);
        cp.setMargins(dp(4), 0, dp(4), 0);
        row.addView(btnPlay, cp);
        row.addView(btnShare, cp);
        row.addView(btnDel, cp);

        btnRec.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (saving) {
                    status.setText("استنى لحد ما الحفظ يخلص");
                } else if (recording) {
                    stopRecord();
                } else {
                    startRecord();
                }
            }
        });
        btnPlay.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { playLast(); }
        });
        btnShare.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { shareLast(); }
        });
        btnDel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { deleteLast(); }
        });

        setContentView(root);
    }

    private Button actionButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.parseColor("#E0E0E0"));
        b.setTextSize(15);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor("#2A2A2A"));
        g.setCornerRadius(dp(10));
        b.setBackground(g);
        return b;
    }

    private void setActionButtons(boolean enabled) {
        btnPlay.setEnabled(enabled);
        btnShare.setEnabled(enabled);
        btnDel.setEnabled(enabled);
    }

    // ---------- recording ----------

    private void startRecord() {
        if (saving) { status.setText("استنى لحد ما الحفظ يخلص"); return; }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_PERM);
            status.setText("محتاج إذن المايك الأول");
            return;
        }
        wave.reset();
        recording = true;
        btnRec.setText("■ إيقاف");
        status.setText("بتسجل...");
        capThread = new Thread(new Runnable() { public void run() { captureLoop(); } });
        capThread.start();
    }

    private void stopRecord() {
        if (!recording || saving) return;
        recording = false;
        showSavingUi();
    }

    private void showSavingUi() {
        saving = true;
        btnRec.setText("● سجل");
        setActionButtons(false);
        status.setText("جاري الحفظ...");
    }

    private AudioRecord openRecorder(int minBuf) {
        int[] srcs = {MediaRecorder.AudioSource.CAMCORDER,
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.DEFAULT};
        String[] names = {"كاميرا", "خام", "افتراضي"};
        for (int i = 0; i < srcs.length; i++) {
            AudioRecord cand = null;
            try {
                cand = new AudioRecord(srcs[i], SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2);
                if (cand.getState() == AudioRecord.STATE_INITIALIZED) {
                    cand.startRecording();
                    if (cand.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        final String sn = names[i];
                        ui.post(new Runnable() { public void run() {
                            if (recording) status.setText("بتسجل... (المصدر: " + sn + ")");
                        } });
                        return cand;
                    }
                }
            } catch (Throwable ignore) { }
            if (cand != null) { try { cand.release(); } catch (Throwable ignore) { } }
        }
        return null;
    }

    private void captureLoop() {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = 8192;

        AudioRecord ar = openRecorder(minBuf);
        if (ar == null) {
            recording = false;
            ui.post(new Runnable() { public void run() {
                btnRec.setText("● سجل");
                status.setText("خطأ في فتح المايك");
            } });
            return;
        }

        // fresh session state
        File dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (dir == null) dir = getFilesDir();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        outFile = new File(dir, "REC_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".m4a");
        channel = -1;
        sumL = 0; sumR = 0;
        ptsUs = 0;
        totalMonoSamples = 0;
        sessionPeak = 0;
        winCount = 0;
        gatePrevGain = 1f;
        Arrays.fill(rmsHist, 0);
        muxerStarted = false;
        enc = null;
        muxer = null;

        ByteArrayOutputStream pending = new ByteArrayOutputStream(SAMPLE_RATE * 4 + 8192);
        byte[] gateLeft = new byte[0];
        String err = null;
        boolean auto = false;
        try {
            enc = MediaCodec.createEncoderByType("audio/mp4a-latm");
            MediaFormat fmt = MediaFormat.createAudioFormat("audio/mp4a-latm", SAMPLE_RATE, 1);
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            muxer = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            byte[] buf = new byte[minBuf];
            int maxBytes = SAMPLE_RATE * 2 * 2 * MAX_SECONDS;
            long total = 0;
            int sincePost = 0;
            int chunkPeak = 0;
            while (recording) {
                int n = ar.read(buf, 0, buf.length);
                if (n <= 0) continue;
                total += n;
                for (int i = 0; i + 3 < n; i += 4) {
                    int l = (buf[i] & 0xff) | (buf[i + 1] << 8);
                    int r = (buf[i + 2] & 0xff) | (buf[i + 3] << 8);
                    int m = Math.max(Math.abs((short) l), Math.abs((short) r));
                    if (m > chunkPeak) chunkPeak = m;
                }
                sincePost += n;
                if (sincePost >= 9600) { // ~50 ms metering for the waveform
                    final float amp = Math.min(1f, chunkPeak / 32768f);
                    ui.post(new Runnable() { public void run() { wave.addAmplitude(amp); } });
                    sincePost = 0;
                    chunkPeak = 0;
                }
                if (channel < 0) {
                    accumulateSums(buf, 0, n);
                    pending.write(buf, 0, n);
                    if (pending.size() >= SAMPLE_RATE * 4) { // 1 s of stereo: lock the channel
                        channel = sumR > sumL ? 1 : 0;
                        byte[] all = pending.toByteArray();
                        pending = new ByteArrayOutputStream(0);
                        gateLeft = feedPcm(all, 0, all.length, gateLeft);
                    }
                } else {
                    gateLeft = feedPcm(buf, 0, n, gateLeft);
                }
                if (total >= maxBytes) {
                    recording = false;
                    auto = true;
                }
            }
            try { ar.stop(); } catch (Throwable ignore) { }
            try { ar.release(); } catch (Throwable ignore) { }
            ar = null;
            if (auto) {
                ui.post(new Runnable() { public void run() { showSavingUi(); } });
            }
            // short recording: decide the channel from whatever we heard and flush
            if (channel < 0) {
                channel = sumR > sumL ? 1 : 0;
                byte[] all = pending.toByteArray();
                gateLeft = feedPcm(all, 0, all.length, gateLeft);
            }
            pending = null;
            // sub-20ms leftover goes in ungated
            if (gateLeft.length > 0) {
                feedEncoderBytes(gateLeft, 0, gateLeft.length);
                totalMonoSamples += gateLeft.length / 2;
            }
            signalEosAndDrain();
            cleanupCodec();
            if (!outFile.exists() || outFile.length() == 0) {
                throw new IllegalStateException("الملف مش موجود بعد الترميز");
            }
            if (totalMonoSamples < SAMPLE_RATE / 5) {
                try { outFile.delete(); } catch (Throwable ignore) { }
                postResult(null, "SHORT");
                return;
            }
            postResult(outFile, null);
        } catch (Throwable t) {
            err = t.getClass().getSimpleName() + ": " + (t.getMessage() != null ? t.getMessage() : "");
            if (ar != null) {
                try { ar.stop(); } catch (Throwable ignore) { }
                try { ar.release(); } catch (Throwable ignore) { }
            }
            cleanupCodec();
            if (outFile != null) { try { outFile.delete(); } catch (Throwable ignore) { } }
            postResult(null, err);
        }
    }

    private void cleanupCodec() {
        if (enc != null) {
            try { enc.stop(); } catch (Throwable ignore) { }
            try { enc.release(); } catch (Throwable ignore) { }
            enc = null;
        }
        if (muxer != null) {
            if (muxerStarted) { try { muxer.stop(); } catch (Throwable ignore) { } }
            try { muxer.release(); } catch (Throwable ignore) { }
            muxer = null;
            muxerStarted = false;
        }
    }

    private void postResult(final File f, final String err) {
        final int ch = channel < 0 ? 0 : channel;
        final long p = sessionPeak;
        ui.post(new Runnable() { public void run() {
            saving = false;
            setActionButtons(true);
            btnRec.setText("● سجل");
            if (f != null) {
                lastFile = f;
                long kb = Math.max(1, f.length() / 1024);
                status.setText("اتحفظ التسجيل (" + kb + " كيلو) — قناة "
                        + (ch == 1 ? "يمين" : "شمال") + " — أعلى صوت " + (p * 100 / 32767)
                        + "% — تنقية الصوت شغالة");
            } else if ("SHORT".equals(err)) {
                status.setText("التسجيل قصير جدًا، جرب تاني");
            } else {
                status.setText("خطأ في حفظ التسجيل: " + err);
            }
        } });
    }

    // ---------- incremental PCM pipeline (capture thread only) ----------

    private void accumulateSums(byte[] b, int off, int len) {
        for (int i = off; i + 3 < off + len; i += 4) {
            short l = (short) ((b[i] & 0xff) | (b[i + 1] << 8));
            short r = (short) ((b[i + 2] & 0xff) | (b[i + 3] << 8));
            sumL += Math.abs(l);
            sumR += Math.abs(r);
        }
    }

    /** Convert stereo frames to gated mono and feed the encoder. Returns the sub-20ms leftover. */
    private byte[] feedPcm(byte[] stereo, int off, int len, byte[] gateLeft) throws Exception {
        int frames = len / 4;
        byte[] mono = new byte[gateLeft.length + frames * 2];
        System.arraycopy(gateLeft, 0, mono, 0, gateLeft.length);
        int base0 = channel * 2;
        for (int f = 0; f < frames; f++) {
            int b = off + f * 4 + base0;
            short s = (short) ((stereo[b] & 0xff) | (stereo[b + 1] << 8));
            int g = (int) (s * GAIN);
            if (g > 32767) g = 32767;
            if (g < -32768) g = -32768;
            int m = gateLeft.length + f * 2;
            mono[m] = (byte) (g & 0xff);
            mono[m + 1] = (byte) ((g >> 8) & 0xff);
        }
        int totalSamples = mono.length / 2;
        int whole = (totalSamples / GATE_WIN) * GATE_WIN;
        applyLiveGate(mono, whole);
        feedEncoderBytes(mono, 0, whole * 2);
        totalMonoSamples += whole;
        byte[] left = new byte[(totalSamples - whole) * 2];
        System.arraycopy(mono, whole * 2, left, 0, left.length);
        return left;
    }

    /**
     * Live adaptive noise gate: the noise floor is estimated from the quietest
     * 10% of the 20 ms windows seen so far (histogram), only after the first
     * second of audio. Windows below 2x the floor fade toward silence (min
     * gain 0.1) with per-sample ramping so there are no clicks.
     */
    private void applyLiveGate(byte[] mono, int uptoSamples) {
        int nw = uptoSamples / GATE_WIN;
        for (int w = 0; w < nw; w++) {
            long sum = 0;
            for (int i = 0; i < GATE_WIN; i++) {
                int idx = (w * GATE_WIN + i) * 2;
                short s = (short) ((mono[idx] & 0xff) | (mono[idx + 1] << 8));
                sum += (long) s * s;
            }
            double rms = Math.sqrt(sum / (double) GATE_WIN);
            float target = 1f;
            if (winCount >= GATE_WARMUP_WINDOWS) {
                double thresh = Math.max(2.0 * floorFromHist(), 20.0);
                if (rms < thresh) target = 0.1f;
            }
            for (int i = 0; i < GATE_WIN; i++) {
                float g = gatePrevGain + (target - gatePrevGain) * (i / (float) GATE_WIN);
                int idx = (w * GATE_WIN + i) * 2;
                short s = (short) ((mono[idx] & 0xff) | (mono[idx + 1] << 8));
                int v = (int) (s * g);
                if (v > 32767) v = 32767;
                if (v < -32768) v = -32768;
                mono[idx] = (byte) (v & 0xff);
                mono[idx + 1] = (byte) ((v >> 8) & 0xff);
                int a = v < 0 ? -v : v;
                if (a > sessionPeak) sessionPeak = a;
            }
            gatePrevGain = target;
            int bucket = (int) (rms / 50.0);
            if (bucket >= rmsHist.length) bucket = rmsHist.length - 1;
            rmsHist[bucket]++;
            winCount++;
        }
    }

    private double floorFromHist() {
        long need = Math.max(1, winCount / 10);
        double sum = 0;
        long used = 0;
        for (int b = 0; b < rmsHist.length && need > 0; b++) {
            int c = rmsHist[b];
            if (c == 0) continue;
            long take = Math.min(c, need);
            sum += take * (b * 50.0 + 25.0);
            used += take;
            need -= take;
        }
        return used > 0 ? sum / used : 0.0;
    }

    private void feedEncoderBytes(byte[] mono, int off, int len) throws Exception {
        long deadline = System.currentTimeMillis() + 10000;
        while (len > 0) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("encoder input timeout");
            }
            int idx = enc.dequeueInputBuffer(10000);
            if (idx >= 0) {
                ByteBuffer ib = enc.getInputBuffer(idx);
                ib.clear();
                int chunk = Math.min(ib.capacity(), len);
                chunk &= ~1;
                if (chunk <= 0) continue;
                ib.put(mono, off, chunk);
                enc.queueInputBuffer(idx, 0, chunk, ptsUs, 0);
                ptsUs += (long) (chunk / 2) * 1000000L / SAMPLE_RATE;
                off += chunk;
                len -= chunk;
            }
            drainOutput(false);
        }
        drainOutput(false);
    }

    private void signalEosAndDrain() throws Exception {
        long deadline = System.currentTimeMillis() + 30000;
        boolean eosQueued = false;
        while (true) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("encoder drain timeout");
            }
            if (!eosQueued) {
                int idx = enc.dequeueInputBuffer(10000);
                if (idx >= 0) {
                    enc.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    eosQueued = true;
                }
            }
            if (drainOutput(true)) return;
        }
    }

    /** Drain encoder output into the muxer. Returns true once EOS is seen. */
    private boolean drainOutput(boolean blocking) {
        while (true) {
            int oi = enc.dequeueOutputBuffer(bufInfo, blocking ? 20000 : 0);
            if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return false;
            } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted) {
                    muxTrack = muxer.addTrack(enc.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                }
            } else if (oi >= 0) {
                ByteBuffer ob = enc.getOutputBuffer(oi);
                if (muxerStarted && bufInfo.size > 0
                        && (bufInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    ob.position(bufInfo.offset);
                    ob.limit(bufInfo.offset + bufInfo.size);
                    muxer.writeSampleData(muxTrack, ob, bufInfo);
                }
                enc.releaseOutputBuffer(oi, false);
                if ((bufInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true;
            }
        }
    }

    // ---------- actions ----------

    private boolean checkFile() {
        if (lastFile == null || !lastFile.exists() || lastFile.length() == 0) {
            lastFile = null;
            status.setText("لا يوجد تسجيل");
            return false;
        }
        return true;
    }

    private void stopPlayer() {
        if (player != null) {
            try { player.stop(); } catch (Throwable ignore) { }
            try { player.release(); } catch (Throwable ignore) { }
            player = null;
        }
    }

    private void playLast() {
        if (saving || !checkFile()) return;
        stopPlayer();
        try {
            player = new MediaPlayer();
            player.setDataSource(lastFile.getAbsolutePath());
            player.prepare();
            player.start();
            status.setText("بتسمع...");
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer mp) {
                    stopPlayer();
                    if (lastFile != null && lastFile.exists()) {
                        status.setText("اتحفظ التسجيل (" + Math.max(1, lastFile.length() / 1024) + " كيلو)");
                    }
                }
            });
        } catch (Throwable t) {
            stopPlayer();
            status.setText("خطأ في التشغيل: " + t.getClass().getSimpleName()
                    + ": " + (t.getMessage() != null ? t.getMessage() : ""));
        }
    }

    private void shareLast() {
        if (saving || !checkFile()) return;
        Uri uri = Uri.parse("content://com.instinct.micfix.audio/" + lastFile.getName());
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("audio/mp4");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.setClipData(ClipData.newUri(getContentResolver(), "تسجيل", uri));
        try {
            startActivity(Intent.createChooser(i, "ابعت التسجيل"));
        } catch (Throwable t) {
            status.setText("خطأ في المشاركة: " + t.getClass().getSimpleName());
        }
    }

    private void deleteLast() {
        if (saving) return;
        stopPlayer();
        if (lastFile == null || !lastFile.exists()) {
            lastFile = null;
            status.setText("لا يوجد تسجيل");
            return;
        }
        boolean ok = lastFile.delete();
        lastFile = null;
        status.setText(ok ? "اتمسح التسجيل" : "مش قادر أمسح الملف");
    }
}
