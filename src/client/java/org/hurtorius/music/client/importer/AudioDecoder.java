package org.hurtorius.music.client.importer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/**
 * Reads an audio file into mono PCM. The format is recognised from the file's contents, not its
 * name: WAV, AIFF and AU (through {@code javax.sound.sampled}), OGG Vorbis (Minecraft's JOrbis),
 * MP3 (MPEG-1, through the bundled javamp3) and FLAC (our own decoder). Files may be up to
 * {@link #MAX_FILE_BYTES} and {@link #MAX_SECONDS} long. Every failure is an
 * {@link AudioImportException} whose message can be shown to the player as it is.
 *
 * <p>Decoding blocks; run it off the client thread (see {@link Transcriber#importAudio}).</p>
 */
public final class AudioDecoder {
    private static final Logger LOG = LoggerFactory.getLogger("Music");
    public static final long MAX_FILE_BYTES = 400L * 1024 * 1024;
    public static final int MAX_SECONDS = 30 * 60;
    /** File name extensions of the supported formats, lower case, for file pickers. */
    public static final List<String> EXTENSIONS = List.of("mp3", "wav", "ogg", "flac", "aif", "aiff", "aifc", "au", "snd", "oga", "wave");

    private AudioDecoder() {}

    /** True if the file name has one of the {@link #EXTENSIONS}. */
    public static boolean hasAudioExtension(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** Decodes the whole file at its own sample rate. */
    public static DecodedAudio decode(Path file) throws AudioImportException {
        return decode(file, Integer.MAX_VALUE, v -> {}, () -> false);
    }

    /**
     * Decodes the file, resampling on the fly to {@code maxSampleRate} if its own rate is higher, so
     * that a long high-rate file never has to be held in memory at full rate.
     *
     * @param progress  receives 0..1 as the file is read (on the calling thread)
     * @param cancelled polled while decoding; when it returns true a {@link CancellationException} is thrown
     */
    public static DecodedAudio decode(Path file, int maxSampleRate, DoubleConsumer progress, BooleanSupplier cancelled)
        throws AudioImportException {
        return decode(file, maxSampleRate, Progress.of(progress, cancelled));
    }

    static DecodedAudio decode(Path file, int maxSampleRate, Progress progress) throws AudioImportException {
        long size = checkFile(file);
        progress.at(0);
        try (CountingInputStream counting = new CountingInputStream(Files.newInputStream(file));
             InputStream in = new BufferedInputStream(counting, 1 << 16)) {
            progress.track(counting::count, size);
            Format format = sniff(in, file);
            DecodedAudio audio = switch (format) {
                case WAV -> SampledDecoder.decode(in, "WAV", size, maxSampleRate, progress);
                case AIFF -> SampledDecoder.decode(in, "AIFF", size, maxSampleRate, progress);
                case AU -> SampledDecoder.decode(in, "AU", size, maxSampleRate, progress);
                case OGG -> OggDecoder.decode(in, file, maxSampleRate, progress);
                case MP3 -> Mp3Decoder.decode(in, file, size, maxSampleRate, progress);
                case FLAC -> FlacDecoder.decode(in, maxSampleRate, progress);
            };
            progress.at(1);
            LOG.info("Decoded {} ({}): {} s at {} Hz", file.getFileName(), format,
                String.format(Locale.ROOT, "%.2f", audio.seconds()), audio.sampleRate());
            return audio;
        } catch (AudioImportException | CancellationException e) {
            throw e;
        } catch (OutOfMemoryError e) {
            throw new AudioImportException("Not enough memory to decode this file. Give Minecraft more memory or import a shorter file.", e);
        } catch (IOException e) {
            throw new AudioImportException("Could not read " + file.getFileName() + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            LOG.error("Decoder failure on {}", file, e);
            throw new AudioImportException("This file could not be decoded (" + e + ").", e);
        }
    }

    private static long checkFile(Path file) throws AudioImportException {
        try {
            if (!Files.exists(file)) throw new AudioImportException("The file " + file + " does not exist.");
            if (!Files.isRegularFile(file)) throw new AudioImportException(file + " is not a file.");
            long size = Files.size(file);
            if (size == 0) throw new AudioImportException("The file " + file.getFileName() + " is empty.");
            if (size > MAX_FILE_BYTES) {
                throw new AudioImportException(String.format(Locale.ROOT, "%s is %.0f MB; at most %d MB can be imported.",
                    file.getFileName(), size / 1048576.0, MAX_FILE_BYTES / 1048576));
            }
            return size;
        } catch (NoSuchFileException e) {
            throw new AudioImportException("The file " + file + " does not exist.", e);
        } catch (AccessDeniedException e) {
            throw new AudioImportException("Not allowed to read " + file + ".", e);
        } catch (AudioImportException e) {
            throw e;
        } catch (IOException e) {
            throw new AudioImportException("Could not read " + file + ": " + e.getMessage(), e);
        }
    }

    private enum Format { WAV, AIFF, AU, OGG, MP3, FLAC }

    /** Recognises the format from the first bytes (after any ID3v2 tag). */
    private static Format sniff(InputStream in, Path file) throws IOException {
        in.mark(64);
        byte[] h = in.readNBytes(64);
        in.reset();
        String ascii = new String(h, StandardCharsets.ISO_8859_1);
        if (ascii.startsWith("RIFF") && ascii.startsWith("WAVE", 8)) return Format.WAV;
        if (ascii.startsWith("RF64") || ascii.startsWith("BW64")) {
            throw new AudioImportException("This is an RF64/BW64 WAV file, which cannot be read. Save it as a normal WAV, FLAC or OGG.");
        }
        if (ascii.startsWith("FORM") && (ascii.startsWith("AIFF", 8) || ascii.startsWith("AIFC", 8))) return Format.AIFF;
        if (ascii.startsWith(".snd")) return Format.AU;
        if (ascii.startsWith("OggS")) return Format.OGG;
        if (ascii.startsWith("fLaC")) return Format.FLAC;
        if (ascii.startsWith("ID3") && h.length >= 10) {
            long tag = 10 + (((h[6] & 0x7F) << 21) | ((h[7] & 0x7F) << 14) | ((h[8] & 0x7F) << 7) | (h[9] & 0x7F))
                + ((h[5] & 0x10) != 0 ? 10 : 0);
            return flacAfter(file, tag) ? Format.FLAC : Format.MP3;
        }
        if (h.length >= 2 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xE0) == 0xE0) return Format.MP3;
        if (ascii.startsWith("MThd")) throw new AudioImportException("This is a MIDI file. Import it as MIDI instead; it does not need transcribing.");
        if (ascii.startsWith("ftyp", 4)) throw new AudioImportException("AAC/M4A (MP4) audio cannot be decoded. Convert it to MP3, OGG, FLAC or WAV.");
        if (ascii.startsWith("caff")) throw new AudioImportException("Core Audio (CAF) files cannot be decoded. Convert it to WAV, FLAC, OGG or MP3.");
        if (ascii.startsWith("wvpk")) throw new AudioImportException("WavPack files cannot be decoded. Convert it to FLAC or WAV.");
        if (ascii.startsWith("MAC ")) throw new AudioImportException("Monkey's Audio files cannot be decoded. Convert it to FLAC or WAV.");
        if (h.length >= 4 && (h[0] & 0xFF) == 0x30 && (h[1] & 0xFF) == 0x26 && (h[2] & 0xFF) == 0xB2 && (h[3] & 0xFF) == 0x75) {
            throw new AudioImportException("WMA files cannot be decoded. Convert it to MP3, OGG, FLAC or WAV.");
        }
        if (ascii.startsWith("#!AMR")) throw new AudioImportException("AMR files cannot be decoded. Convert it to MP3, OGG, FLAC or WAV.");
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".mp3")) return Format.MP3;
        throw new AudioImportException("This file is not a recognised audio format. Supported: MP3, WAV, OGG Vorbis, FLAC, AIFF and AU.");
    }

    /**
     * True if a FLAC stream follows the ID3v2 tag at the start (some taggers put one there). Looked at
     * through a second stream: a tag with cover art is far larger than the decoding stream's buffer.
     */
    private static boolean flacAfter(Path file, long tag) throws IOException {
        try (InputStream peek = Files.newInputStream(file)) {
            peek.skipNBytes(tag);
            return new String(peek.readNBytes(4), StandardCharsets.ISO_8859_1).equals("fLaC");
        } catch (EOFException e) {
            return false;
        }
    }
}
