package io.popdesk.helpdesk.photo;

import io.popdesk.helpdesk.photo.PhotoService.Photo;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The answer that carries a photo (S1A-AC-87): always {@code image/png}, with
 * {@code Cache-Control: private, no-cache} and an {@code ETag}, so the browser keeps the photo but asks
 * again every time and the caller's right is checked again; 304 without a body while the caller's copy
 * is the stored one. {@code X-Content-Type-Options: nosniff} comes with the security headers of every
 * response. These two answers are the only ones without {@code no-store}; a 404 has it like any other.
 */
public final class PhotoResponses {

    private static final CacheControl ASK_AGAIN = CacheControl.noCache().cachePrivate();

    private PhotoResponses() {}

    /** 200 with the photo, or 304 when {@code ifNoneMatch} names its {@code ETag}. */
    public static ResponseEntity<Resource> of(Photo photo, String ifNoneMatch) {
        if (matches(ifNoneMatch, photo.etag())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).cacheControl(ASK_AGAIN).eTag(photo.etag()).build();
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(ASK_AGAIN).eTag(photo.etag())
                .body(new ByteArrayResource(photo.png()));
    }

    /** Whether an {@code If-None-Match} value, a list of tags or {@code *}, names the tag; weak tags count. */
    private static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String tag = candidate.strip();
            if (tag.equals("*") || tag.equals(etag) || tag.equals("W/" + etag)) {
                return true;
            }
        }
        return false;
    }
}
