package io.popdesk.helpdesk.branding;

import io.popdesk.ApiError;
import io.popdesk.ApiErrors;
import io.popdesk.ApiException;
import io.popdesk.TextInput;
import io.popdesk.Transactions;
import io.popdesk.helpdesk.AccountRateLimit;
import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.audit.AuditAction;
import io.popdesk.helpdesk.audit.AuditLog;
import io.popdesk.helpdesk.branding.AccentPalette.Palette;
import io.popdesk.helpdesk.branding.BrandingRepository.LogoInfo;
import io.popdesk.helpdesk.branding.BrandingRepository.Row;
import io.popdesk.helpdesk.branding.BrandingRepository.Theme;
import io.popdesk.helpdesk.image.ImageDecoder;
import io.popdesk.helpdesk.image.ImageDecoder.Png;
import io.popdesk.helpdesk.image.ImageDecoding;
import io.popdesk.helpdesk.image.ImageHeader;
import io.popdesk.notification.BrandingName;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * The branding of the installation (BRAND-1 to BRAND-5): the display name, the accent color, and a logo for each
 * theme. One for the whole installation (NG-07), read from the database on every call (slice 9 design 0절 4).
 *
 * <p>The display name and the accent color are saved together with a version (S9-AC-07, S9-AC-08); a logo is put
 * in or removed on its own and leaves the version as it is (S9-AC-18). Every write locks the one branding row
 * first, {@code FOR NO KEY UPDATE}, so two writes of either kind take turns and an audit entry's "from" is the value
 * the write replaced (design 1절).
 */
@Service
public class BrandingService implements BrandingName {

    private static final Logger log = LoggerFactory.getLogger(BrandingService.class);

    /** The longest display name, in code points (BRAND-1). */
    public static final int NAME_MAX = 50;

    /** The largest logo file: 512 KB, 524,288 bytes (BRAND-2). */
    static final long MAX_FILE = 512 * 1024;

    /** The stored logo fits this box (acceptance.md 5절 5). */
    static final int BOX_WIDTH = 512;

    static final int BOX_HEIGHT = 128;

    /** The start of a public logo address, before the theme. */
    static final String LOGO_PATH = "/api/public/branding/logos/";

    /** A stored logo as an admin reads it (S9-AC-06): its public address, and its size in pixels. */
    public record Logo(String url, int width, int height) {}

    /**
     * The branding as an admin edits it (S9-AC-06).
     *
     * @param accent {@code #RRGGBB} in capitals, or null for the app's own colors
     * @param palette what {@code accent} makes; null without one
     * @param light the light theme's own logo, or null
     * @param dark the dark theme's own logo, or null
     */
    public record Settings(long version, String displayName, String accent, Palette palette, Logo light, Logo dark) {}

    /**
     * The branding anyone may read (S9-AC-01 to S9-AC-03): nothing an admin reads besides.
     *
     * @param palette null without an accent color
     * @param light the address of the light theme's logo, the dark one's when it has none, or null
     * @param dark the same for the dark theme
     */
    public record Shown(String displayName, Palette palette, String light, String dark) {}

    /** A logo as it is served: the PNG and its fingerprint. */
    public record LogoImage(byte[] png, String fingerprint) {}

    /** A change as sent; any part may be null. */
    public record Change(Long version, String displayName, String accent) {}

    private final BrandingRepository repository;
    private final AuditLog audit;
    private final AccountRateLimit rateLimit;
    private final ImageDecoding decoding;
    private final Transactions transactions;
    private final Clock clock;

    BrandingService(BrandingRepository repository, AuditLog audit, AccountRateLimit rateLimit, ImageDecoding decoding,
            Transactions transactions, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.rateLimit = rateLimit;
        this.decoding = decoding;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** The branding for an admin (S9-AC-06). */
    @Transactional(readOnly = true)
    public Settings get(Viewer viewer) {
        adminOnly(viewer);
        return settings(repository.find());
    }

    /**
     * Saves the display name and the accent color (S9-AC-07 to S9-AC-11). Checks, in this order: the role (403),
     * every field (400, all listed), then under the locked row the version (409, also when the values are the ones
     * saved). Values that are the saved ones once cleaned up (the name trimmed and in NFC, the color in capitals)
     * change nothing and leave no audit entry; otherwise the next version is saved and one {@code BRANDING_CHANGED}
     * entry names the fields that changed (S9-AC-20).
     */
    public Settings update(Viewer viewer, AuditLog.Actor admin, Change change) {
        adminOnly(viewer);
        List<ApiError.FieldError> errors = new ArrayList<>();
        if (change.version() == null) {
            errors.add(new ApiError.FieldError("version", "REQUIRED"));
        }
        String name = displayName(change.displayName(), errors);
        String accent = accent(change.accent(), errors);
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors);
        }
        return transactions.run(() -> {
            Row row = repository.lock();
            if (change.version() != row.version()) {
                throw ApiException.conflict("VERSION_CONFLICT", "Someone changed the branding. Read it again.");
            }
            if (row.displayName().equals(name) && Objects.equals(row.accent(), accent)) {
                return settings(row);
            }
            long next = row.version() + 1;
            repository.update(next, name, accent, admin.accountId(), now());
            Map<String, Object> changes = new LinkedHashMap<>();
            if (!row.displayName().equals(name)) {
                changes.put("displayName", fromTo(row.displayName(), name));
            }
            if (!Objects.equals(row.accent(), accent)) {
                changes.put("accent", fromTo(row.accent(), accent));
            }
            audit.record(admin, AuditAction.BRANDING_CHANGED, Map.of("type", "BRANDING"), changes);
            return settings(new Row(next, name, accent));
        });
    }

    /**
     * What a color would make, saving nothing (S9-AC-12): 400 {@code color} {@code REQUIRED} without one, and
     * {@code INVALID_FORMAT} when it is not {@code #RRGGBB}.
     */
    public Palette preview(Viewer viewer, String color) {
        adminOnly(viewer);
        if (color == null || color.isEmpty()) {
            throw ApiException.invalid("color", "REQUIRED");
        }
        if (!AccentPalette.valid(color)) {
            throw ApiException.invalid("color", "INVALID_FORMAT");
        }
        return AccentPalette.of(color);
    }

    /**
     * Puts a logo in for a theme (S9-AC-15, S9-AC-16, S9-AC-19, S9-AC-21). Checks, in this order, and nothing later
     * runs for a request an earlier step refused: the role (403), the rate limit (429, refused uploads count too),
     * the theme (404), the size of the file (413), a place among the images being decoded (503), the format by the
     * file's first bytes and the pixel count in its headers (400), and only then the decoding, outside any
     * transaction. Under the locked branding row, a logo with the fingerprint the theme has already changes nothing
     * and leaves no audit entry.
     */
    public Logo uploadLogo(Viewer viewer, AuditLog.Actor admin, String theme, MultipartFile file) {
        adminOnly(viewer);
        rateLimit.count(AccountRateLimit.Action.BRAND_LOGO, viewer.accountId());
        Theme which = theme(theme);
        if (file.getSize() > MAX_FILE) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, ApiErrors.PAYLOAD_TOO_LARGE,
                    ApiErrors.PAYLOAD_TOO_LARGE_MESSAGE);
        }
        Png png = decoding.run(() -> convert(viewer, file));
        String sha256 = sha256(png.bytes());
        transactions.run(() -> {
            repository.lock();
            Optional<String> before = repository.sha256(which);
            if (before.isPresent() && before.get().equals(sha256)) {
                return;
            }
            repository.putLogo(which, png.bytes(), sha256, png.width(), png.height(), admin.accountId(), now());
            recordLogo(admin, which, before.map(BrandingService::fingerprint).orElse(null), fingerprint(sha256));
        });
        return new Logo(url(which, fingerprint(sha256)), png.width(), png.height());
    }

    /**
     * Removes the logo of a theme (S9-AC-17, S9-AC-21): 404 for a theme other than {@code light} and {@code dark};
     * nothing happens, and no audit entry, when the theme has no logo.
     */
    public void deleteLogo(Viewer viewer, AuditLog.Actor admin, String theme) {
        adminOnly(viewer);
        Theme which = theme(theme);
        transactions.run(() -> {
            repository.lock();
            Optional<String> before = repository.sha256(which);
            if (before.isEmpty()) {
                return;
            }
            repository.deleteLogo(which);
            recordLogo(admin, which, fingerprint(before.get()), null);
        });
    }

    /** The public branding (S9-AC-01 to S9-AC-03, S9-AC-05). */
    @Transactional(readOnly = true)
    public Shown shown() {
        Row row = repository.find();
        Map<Theme, LogoInfo> logos = repository.logos();
        String light = address(Theme.LIGHT, logos.get(Theme.LIGHT));
        String dark = address(Theme.DARK, logos.get(Theme.DARK));
        return new Shown(row.displayName(), palette(row.accent()), light == null ? dark : light,
                dark == null ? light : dark);
    }

    /**
     * The logo of a theme as {@code light} or {@code dark} names it in the address (S9-AC-04); empty for a theme
     * without a logo and for any other name, {@code LIGHT} too. Never the other theme's logo.
     */
    @Transactional(readOnly = true)
    public Optional<LogoImage> logo(String theme) {
        Optional<Theme> which = themeOf(theme);
        if (which.isEmpty()) {
            return Optional.empty();
        }
        return repository.logo(which.get()).map(l -> new LogoImage(l.png(), fingerprint(l.sha256())));
    }

    @Override
    @Transactional(readOnly = true)
    public String displayName() {
        return repository.find().displayName();
    }

    private Settings settings(Row row) {
        Map<Theme, LogoInfo> logos = repository.logos();
        return new Settings(row.version(), row.displayName(), row.accent(), palette(row.accent()),
                logo(Theme.LIGHT, logos.get(Theme.LIGHT)), logo(Theme.DARK, logos.get(Theme.DARK)));
    }

    private static Palette palette(String accent) {
        return accent == null ? null : AccentPalette.of(accent);
    }

    private static Logo logo(Theme theme, LogoInfo info) {
        return info == null ? null : new Logo(url(theme, fingerprint(info.sha256())), info.width(), info.height());
    }

    private static String address(Theme theme, LogoInfo info) {
        return info == null ? null : url(theme, fingerprint(info.sha256()));
    }

    /** {@code /api/public/branding/logos/{theme}?v={fingerprint}}. */
    private static String url(Theme theme, String fingerprint) {
        return LOGO_PATH + theme.name().toLowerCase(Locale.ROOT) + "?v=" + fingerprint;
    }

    /**
     * The fingerprint of a logo: the first 16 characters of the SHA-256 of its PNG, in lower case. The {@code ETag},
     * the {@code v} of its address, and the audit entry's {@code logo} are all this (design 8절).
     */
    private static String fingerprint(String sha256) {
        return sha256.substring(0, 16);
    }

    private static String sha256(byte[] png) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void recordLogo(AuditLog.Actor admin, Theme theme, String from, String to) {
        audit.record(admin, AuditAction.BRANDING_LOGO_CHANGED, Map.of("type", "BRANDING", "theme", theme.name()),
                Map.of("logo", fromTo(from, to)));
    }

    private Png convert(Viewer viewer, MultipartFile file) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ImageHeader header = ImageHeader.read(content).orElseThrow(BrandingService::unsupported);
        try {
            return ImageDecoder.toPng(content, header, BOX_WIDTH, BOX_HEIGHT);
        } catch (ImageDecoder.TooManyPixelsException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IMAGE_TOO_LARGE", "The image has too many pixels.");
        } catch (IOException | RuntimeException e) {
            // Whatever a decoder throws on a broken file. An OutOfMemoryError is not caught here.
            log.info("A branding logo of account {} could not be decoded ({}).", viewer.accountId(),
                    e.getClass().getSimpleName());
            throw unsupported();
        }
    }

    private static ApiException unsupported() {
        return new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_IMAGE",
                "The file is not a PNG, JPEG, or WebP image that can be read.");
    }

    /** {@code light} or {@code dark}, in small letters as the contract names them; else 404. */
    private static Theme theme(String theme) {
        return themeOf(theme).orElseThrow(ApiException::notFound);
    }

    private static Optional<Theme> themeOf(String theme) {
        return switch (theme == null ? "" : theme) {
            case "light" -> Optional.of(Theme.LIGHT);
            case "dark" -> Optional.of(Theme.DARK);
            default -> Optional.empty();
        };
    }

    /**
     * The display name (S9-AC-09): the rules of a member's name ({@code MemberService.name}: NFC, trimmed, 1 to 50
     * code points, no control character, line or paragraph separator), and no direction mark either, because the
     * name goes into the {@code From} header and the subject of every email (TM-66).
     */
    static String displayName(String received, List<ApiError.FieldError> errors) {
        String name = TextInput.required("displayName", received, NAME_MAX, errors);
        if (name != null && name.codePoints().anyMatch(BrandingService::refused)) {
            errors.add(new ApiError.FieldError("displayName", "INVALID_FORMAT"));
            return null;
        }
        return name;
    }

    private static boolean refused(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint)
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || codePoint >= 0x202A && codePoint <= 0x202E
                || codePoint >= 0x2066 && codePoint <= 0x2069;
    }

    /** The accent color in capitals, null for the app's own colors; the empty text is a wrong form, not null. */
    private static String accent(String received, List<ApiError.FieldError> errors) {
        if (received == null) {
            return null;
        }
        if (!AccentPalette.valid(received)) {
            errors.add(new ApiError.FieldError("accent", "INVALID_FORMAT"));
            return null;
        }
        if (!AccentPalette.of(received).acceptable()) {
            errors.add(new ApiError.FieldError("accent", "LOW_CONTRAST"));
            return null;
        }
        return AccentPalette.normalized(received);
    }

    private static Map<String, Object> fromTo(Object from, Object to) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("from", from);
        change.put("to", to);
        return change;
    }

    private static void adminOnly(Viewer viewer) {
        if (viewer.role() != Role.ADMIN) {
            throw ApiException.forbidden();
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
