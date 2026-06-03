package com.bintro.transcription;

import com.bintro.Config;
import com.bintro.media.Clip;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Transcribes a {@link Clip}'s audio track via a local whisper.cpp subprocess.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Extract the clip's audio to a temp 16 kHz mono WAV using JavaCV.</li>
 *   <li>Invoke the whisper.cpp binary against the WAV, writing a {@code .txt} sidecar.</li>
 *   <li>Read the sidecar and return its contents.</li>
 * </ol>
 *
 * <p>Temp files are removed in a {@code finally} block.
 */
public class Transcriber {

    public String transcribe(Clip clip) throws IOException {
        Path wav = Files.createTempFile("bintro-", ".wav");
        // whisper.cpp writes "<wav>.txt" alongside the input.
        Path txt = wav.resolveSibling(wav.getFileName() + ".txt");
        try {
            extractAudio(clip, wav);
            runWhisper(wav);
            if (!Files.exists(txt)) {
                // Some whisper.cpp versions strip the extension instead.
                Path alt = wav.resolveSibling(stripExtension(wav.getFileName().toString()) + ".txt");
                if (Files.exists(alt)) {
                    txt = alt;
                } else {
                    throw new IOException("whisper produced no .txt output for " + wav);
                }
            }
            return Files.readString(txt).strip();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Transcription failed for " + clip.filename() + ": " + e.getMessage(), e);
        } finally {
            quietDelete(wav);
            quietDelete(txt);
        }
    }

    private void extractAudio(Clip clip, Path wavOut) throws Exception {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(clip.file())) {
            grabber.start();
            int outChannels = 1;
            int outSampleRate = 16_000;
            try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(wavOut.toFile(), outChannels)) {
                recorder.setFormat("wav");
                recorder.setAudioCodec(avcodec.AV_CODEC_ID_PCM_S16LE);
                recorder.setAudioChannels(outChannels);
                recorder.setSampleRate(outSampleRate);
                recorder.start();

                Frame frame;
                while ((frame = grabber.grabSamples()) != null) {
                    // Pass the source rate/channels; the recorder resamples to its configured output.
                    recorder.recordSamples(frame.sampleRate, frame.audioChannels, frame.samples);
                }
            }
        }
    }

    private void runWhisper(Path wav) throws IOException, InterruptedException {
        String binary = Config.get("whisper.binary.path");
        String model = Config.get("whisper.model.path");
        if (binary == null || binary.isBlank()) {
            throw new IOException("whisper.binary.path is not set in config.properties");
        }
        if (model == null || model.isBlank()) {
            throw new IOException("whisper.model.path is not set in config.properties");
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(binary);
        cmd.add("-m");
        cmd.add(model);
        cmd.add("--output-txt");
        cmd.add("--no-timestamps");
        cmd.add(wav.toString());

        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process proc = pb.start();
        // Drain output so the subprocess doesn't block on a full pipe buffer.
        Thread drain = new Thread(() -> {
            try (var in = proc.getInputStream()) {
                in.transferTo(java.io.OutputStream.nullOutputStream());
            } catch (IOException ignored) {
            }
        }, "whisper-drain");
        drain.setDaemon(true);
        drain.start();

        int rc = proc.waitFor();
        if (rc != 0) {
            throw new IOException("whisper.cpp exited with code " + rc);
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void quietDelete(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }
}
