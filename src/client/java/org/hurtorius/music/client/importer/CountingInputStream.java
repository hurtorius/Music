package org.hurtorius.music.client.importer;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Counts the bytes read through it, for progress reports. */
final class CountingInputStream extends FilterInputStream {
    private long count;

    CountingInputStream(InputStream in) {
        super(in);
    }

    long count() {
        return count;
    }

    @Override
    public int read() throws IOException {
        int b = in.read();
        if (b >= 0) count++;
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = in.read(b, off, len);
        if (n > 0) count += n;
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = in.skip(n);
        if (skipped > 0) count += skipped;
        return skipped;
    }

    @Override
    public boolean markSupported() {
        return false;
    }
}
