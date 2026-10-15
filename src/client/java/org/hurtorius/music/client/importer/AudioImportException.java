package org.hurtorius.music.client.importer;

import java.io.IOException;

/**
 * An audio file could not be imported. The message is written for the player: it says what is wrong
 * with the file and, where it helps, what to do instead (for example which format to convert to).
 */
public final class AudioImportException extends IOException {
    public AudioImportException(String message) {
        super(message);
    }

    public AudioImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
