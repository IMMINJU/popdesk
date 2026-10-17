package io.popdesk.helpdesk.photo;

import io.popdesk.ApiErrors;
import io.popdesk.ApiException;
import io.popdesk.helpdesk.AccountRateLimit;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.image.ImageDecoding;
import io.popdesk.helpdesk.photo.PhotoImages.TooManyPixelsException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

/**
 * Profile photos (ACCT-8, ADR D-52, D-53). An upload is checked in this order, and nothing later runs
 * for a file an earlier step refused: the rate limit (S1A-AC-28), the size of the file (S1A-AC-22), a
 * place among the images being decoded (S1A-AC-88), the format by the file's first bytes, the pixel
 * count in its headers (S1A-AC-23), and only then the decoding. What is stored is the PNG
 * {@link PhotoImages} makes; the uploaded bytes are never written anywhere.
 */
@Service
public class PhotoService {

    private static final Logger log = LoggerFactory.getLogger(PhotoService.class);

    /** The largest file: 2 MB. */
    static final long MAX_FILE = DataSize.ofMegabytes(2).toBytes();

    /** A stored photo and the {@code ETag} of its bytes. */
    public record Photo(byte[] png, String etag) {}

    private final PhotoRepository photos;
    private final AccountRateLimit rateLimit;
    private final ImageDecoding decoding;
    private final Clock clock;

    PhotoService(PhotoRepository photos, AccountRateLimit rateLimit, ImageDecoding decoding, Clock clock) {
        this.photos = photos;
        this.rateLimit = rateLimit;
        this.decoding = decoding;
        this.clock = clock;
    }

    /**
     * Sets the viewer's own photo from an uploaded PNG, JPEG, or WebP file (S1A-AC-20): 413 over 2 MB,
     * 503 while {@value ImageDecoding#AT_ONCE} images, photos and logos together, are being decoded,
     * 400 {@code UNSUPPORTED_IMAGE} for another format or a file that cannot be decoded, 400
     * {@code IMAGE_TOO_LARGE} over the pixel limit.
     */
    public void upload(Viewer viewer, MultipartFile file) {
        rateLimit.count(AccountRateLimit.Action.PHOTO, viewer.accountId());
        if (file.getSize() > MAX_FILE) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, ApiErrors.PAYLOAD_TOO_LARGE,
                    ApiErrors.PAYLOAD_TOO_LARGE_MESSAGE);
        }
        // The file is read into memory only by a request that holds a place, shared with branding logos.
        byte[] png = decoding.run(() -> convert(viewer, file));
        photos.save(viewer.accountId(), png, clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    private byte[] convert(Viewer viewer, MultipartFile file) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        PhotoHeader header = PhotoHeader.read(content).orElseThrow(PhotoService::unsupported);
        try {
            return PhotoImages.toPng(content, header);
        } catch (TooManyPixelsException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IMAGE_TOO_LARGE", "The image has too many pixels.");
        } catch (IOException | RuntimeException e) {
            // Whatever a decoder throws on a broken file. An OutOfMemoryError is not caught here.
            log.info("A profile photo of account {} could not be decoded ({}).", viewer.accountId(),
                    e.getClass().getSimpleName());
            throw unsupported();
        }
    }

    /** Deletes the viewer's own photo; nothing happens when there is none (S1A-AC-24). */
    public void delete(Viewer viewer) {
        photos.delete(viewer.accountId());
    }

    /**
     * The photo of the account, if it has one and the viewer may see it; else 404 for every reason alike
     * (S1A-AC-25 to S1A-AC-27). The right is checked on every call, also when the answer will be "not
     * changed" (S1A-AC-87).
     */
    public Photo get(Viewer viewer, long accountId) {
        byte[] png = photos.find(viewer, accountId).orElseThrow(ApiException::notFound);
        return new Photo(png, etag(png));
    }

    /** A strong {@code ETag}: a new photo has another one even when it was stored in the same instant. */
    private static String etag(byte[] png) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(png);
            return '"' + HexFormat.of().formatHex(hash, 0, 16) + '"';
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException unsupported() {
        return new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_IMAGE",
                "The file is not a PNG, JPEG, or WebP image that can be read.");
    }
}
