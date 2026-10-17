package io.popdesk.helpdesk.image;

import io.popdesk.ApiException;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * The places for decoding an uploaded image, profile photos and branding logos together (S1A-AC-88,
 * S9-AC-19): at most {@value #AT_ONCE} at once. Each holds its file and the decoder's working memory, which
 * for a WebP at the pixel limit is 72 MB of heap (slice 1a design 2). A request that finds no place is
 * refused at once, not queued.
 */
@Component
public class ImageDecoding {

    /** How many images are in memory and being decoded at once. */
    public static final int AT_ONCE = 2;

    /** Seconds a refused client waits before it tries again. */
    public static final long RETRY_AFTER = 1;

    private final Semaphore places = new Semaphore(AT_ONCE);

    /**
     * Runs {@code work}, which reads the file and decodes it, while it holds a place; 503 {@code SERVER_BUSY}
     * with {@code Retry-After} when every place is taken. The file is read into memory only within it.
     */
    public <T> T run(Supplier<T> work) {
        if (!places.tryAcquire()) {
            throw ApiException.busy(RETRY_AFTER);
        }
        try {
            return work.get();
        } finally {
            places.release();
        }
    }
}
