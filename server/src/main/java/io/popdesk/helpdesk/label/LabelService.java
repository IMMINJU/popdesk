package io.popdesk.helpdesk.label;

import io.popdesk.ApiError;
import io.popdesk.ApiException;
import io.popdesk.PatchText;
import io.popdesk.Transactions;
import io.popdesk.helpdesk.CompanyStatus;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.company.CompanyRepository;
import io.popdesk.helpdesk.company.CompanyRepository.CompanyRow;
import io.popdesk.helpdesk.label.LabelRepository.LabelRow;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The labels of a customer company (LBL-1, LBL-3). Staff who see the company read them; admins create,
 * change, and delete them. The company is found in the caller's scope first (404 for one the caller does
 * not see, S6A-AC-11), and a label only under the company of the path (S6A-AC-12). Changing or deleting
 * a label leaves the issues' versions and history as they are (S6A-AC-05, S6A-AC-06).
 */
@Service
public class LabelService {

    /** How many labels a company has at most (TM-56, S6A-AC-09). */
    public static final int COMPANY_MAX = 200;

    private final LabelRepository labels;
    private final CompanyRepository companies;
    private final Transactions transactions;
    private final Clock clock;

    LabelService(LabelRepository labels, CompanyRepository companies, Transactions transactions, Clock clock) {
        this.labels = labels;
        this.companies = companies;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** Every label of a company the viewer sees, in name order whatever the case (S6A-AC-10). */
    @Transactional(readOnly = true)
    public List<LabelRow> list(Viewer viewer, String code) {
        CompanyRow company = companies.findByCode(viewer, code).orElseThrow(ApiException::notFound);
        return labels.list(company.id());
    }

    /**
     * Creates a label (S6A-AC-01). The company row is locked first, so the count of its labels holds until
     * the new one is in (S6A-AC-09); two creations of one name at once are told apart by the unique key,
     * not by a count, and the second gets 409 (S6A-AC-03). Then the input (every invalid field at once),
     * then the company's status (S6A-AC-08) and the limit.
     *
     * @param name the name as sent, or null
     * @param color the color as sent, or null for the default gray
     */
    public LabelRow create(Viewer viewer, String code, String name, String color) {
        return transactions.run(() -> {
            CompanyRow company = companies.lockByCode(viewer, code).orElseThrow(ApiException::notFound);
            adminOnly(viewer);
            List<ApiError.FieldError> errors = new ArrayList<>();
            LabelName labelName = LabelName.of("name", name, errors);
            String labelColor = LabelColor.parse(color).orElse(null);
            if (labelColor == null) {
                errors.add(new ApiError.FieldError("color", "INVALID_FORMAT"));
            }
            if (!errors.isEmpty()) {
                throw ApiException.invalid(errors);
            }
            if (company.status() == CompanyStatus.ARCHIVED) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "COMPANY_NOT_ACTIVE", "The company is not active.");
            }
            if (labels.count(company.id()) >= COMPANY_MAX) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "LABEL_LIMIT_EXCEEDED",
                        "The company has " + COMPANY_MAX + " labels already.");
            }
            long id;
            try {
                id = labels.insert(company.id(), labelName, labelColor, now());
            } catch (DuplicateKeyException e) {
                throw nameTaken();
            }
            return new LabelRow(id, labelName.name(), labelColor, 0);
        });
    }

    /**
     * Changes the name or the color of a label, whichever were sent; neither can be cleared, so
     * {@code null} is {@code REQUIRED} (S6A-AC-02, S6A-AC-04). A name another label of the company has,
     * whatever the case, is 409; the label's own name in other case is not (S6A-AC-03). Works in an
     * {@code ARCHIVED} company too (S6A-AC-08).
     */
    public LabelRow update(Viewer viewer, String code, long id, PatchText name, PatchText color) {
        return transactions.run(() -> {
            CompanyRow company = companies.findByCode(viewer, code).orElseThrow(ApiException::notFound);
            LabelRow label = labels.find(company.id(), id).orElseThrow(ApiException::notFound);
            adminOnly(viewer);
            List<ApiError.FieldError> errors = new ArrayList<>();
            LabelName labelName = name.sent() ? LabelName.of("name", name.value(), errors)
                    : new LabelName(label.name(), LabelName.key(label.name()));
            String labelColor = label.color();
            if (color.sent()) {
                if (color.value() == null) {
                    errors.add(new ApiError.FieldError("color", "REQUIRED"));
                } else {
                    labelColor = LabelColor.parse(color.value()).orElse(null);
                    if (labelColor == null) {
                        errors.add(new ApiError.FieldError("color", "INVALID_FORMAT"));
                    }
                }
            }
            if (!errors.isEmpty()) {
                throw ApiException.invalid(errors);
            }
            if (labelName.name().equals(label.name()) && labelColor.equals(label.color())) {
                return label;
            }
            try {
                if (!labels.update(company.id(), id, labelName, labelColor, now())) {
                    throw ApiException.notFound();
                }
            } catch (DuplicateKeyException e) {
                throw nameTaken();
            }
            return new LabelRow(id, labelName.name(), labelColor, label.issueCount());
        });
    }

    /** Deletes a label of the company and so takes it off every issue, without history (LBL-3, S6A-AC-06). */
    public void delete(Viewer viewer, String code, long id) {
        transactions.run(() -> {
            CompanyRow company = companies.findByCode(viewer, code).orElseThrow(ApiException::notFound);
            adminOnly(viewer);
            if (!labels.delete(company.id(), id)) {
                throw ApiException.notFound();
            }
        });
    }

    /** The security configuration lets only admins reach the writes; this holds should a path slip out of it. */
    private static void adminOnly(Viewer viewer) {
        if (!viewer.admin()) {
            throw ApiException.forbidden();
        }
    }

    private static ApiException nameTaken() {
        return ApiException.conflict("LABEL_NAME_TAKEN", "The company has a label of this name.");
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
