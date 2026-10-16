package io.popdesk.helpdesk.web.console;

import io.popdesk.helpdesk.Priority;
import io.popdesk.helpdesk.account.CurrentAccount;
import io.popdesk.helpdesk.sla.SlaPolicyService;
import io.popdesk.helpdesk.sla.SlaVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The SLA policy (SLA-1, SLA-2), for admins only. Each change is a new version that is never changed
 * again; popdesk works out the due times of running clocks again from the time the version starts
 * (S8A-AC-20). Customers never reach this path: a portal host answers 404 and a console host 401, as for
 * every console path (S8A-AC-05).
 */
@Tag(name = "SLA")
@RestController
@RequestMapping("/api/console/sla-policy")
class ConsoleSlaController {

    static final String ADMIN_ONLY = "`FORBIDDEN`: admins only, agents too (S8A-AC-05).";

    private final SlaPolicyService policies;

    ConsoleSlaController(SlaPolicyService policies) {
        this.policies = policies;
    }

    @Operation(operationId = "getSlaPolicy", summary = "The SLA policy",
            description = "The current version (S8A-AC-01). Checks, in this order: role (403).")
    @ApiResponse(responseCode = "200", description = "The policy.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY)
    @GetMapping
    SlaPolicy getSlaPolicy() {
        return policy(policies.get(CurrentAccount.get().viewer()));
    }

    @Operation(operationId = "updateSlaPolicy", summary = "Change the SLA policy",
            description = "Replaces the goals, business hours, and holidays as a whole and makes the next version "
                    + "(S8A-AC-02). Issues filed from then on get due times; issues filed while SLA was off never "
                    + "do (S8A-AC-11). The due times of running clocks are worked out again within minutes: what "
                    + "counted before the new version stays, and the rest of the goal counts from then by the new "
                    + "version (S8A-AC-20, S8A-AC-22). Sending what the policy has already is 200 with the policy "
                    + "as it is and no new version. A new version leaves an audit entry `SLA_POLICY_CHANGED` with "
                    + "the target `{\"type\": \"SLA_POLICY\", \"version\": …}` (the new version) and, in "
                    + "`changes`, `targets`, `hours`, or `holidays` from before to after for each that changed "
                    + "(S8A-AC-06). Checks, in this order: role (403), the body (400), `version` (409).")
    @ApiResponse(responseCode = "200", description = "The policy as it is now.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`, nothing changed (S8A-AC-04); `fields` "
            + "lists all, named with the place in the list, for example `targets[2].resolutionMinutes`: a goal "
            + "out of 1 to 525,600 (`targets[i].firstResponseMinutes` or `targets[i].resolutionMinutes`, "
            + "`OUT_OF_RANGE`); a resolution goal shorter than the first response goal "
            + "(`targets[i].resolutionMinutes`, `OUT_OF_RANGE`); priorities not the four, one twice, or one "
            + "unknown (`targets`, `UNKNOWN_VALUE`); weekdays not the seven, one twice, or one unknown "
            + "(`hours`, `UNKNOWN_VALUE`); only one of `opens` and `closes` (the empty one, `hours[i].opens` or "
            + "`hours[i].closes`, `REQUIRED`); a time not `HH:MM` in whole minutes, or `24:00` in `opens` "
            + "(`hours[i].opens` or `hours[i].closes`, `INVALID_FORMAT`); `closes` not later than `opens` "
            + "(`hours[i].closes`, `OUT_OF_RANGE`); over 500 holidays (`holidays`, `TOO_MANY`); a day twice "
            + "(`holidays[i]`, the later one, `UNKNOWN_VALUE`); `version` missing (`version`, `REQUIRED`).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = ADMIN_ONLY + " `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "409", description = "`VERSION_CONFLICT`: `version` is not the policy's current "
            + "one, for example because another admin saved first; also when the values sent are the ones the "
            + "policy has now. Nothing changed. Read the policy again (S8A-AC-03).")
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    SlaPolicy updateSlaPolicy(@RequestBody ChangeSlaPolicyRequest request) {
        CurrentAccount admin = CurrentAccount.get();
        List<SlaPolicyService.TargetInput> targets = request.targets() == null ? null : request.targets().stream()
                .map(t -> t == null ? null : new SlaPolicyService.TargetInput(t.priority(), t.firstResponseMinutes(),
                        t.resolutionMinutes()))
                .toList();
        List<SlaPolicyService.HoursInput> hours = request.hours() == null ? null : request.hours().stream()
                .map(h -> h == null ? null : new SlaPolicyService.HoursInput(h.weekday(), h.opens(), h.closes()))
                .toList();
        return policy(policies.change(admin.viewer(), admin.actor(), new SlaPolicyService.Change(request.version(),
                targets, hours, request.holidays())));
    }

    /** A version in the shape of the API: the four priorities and the seven weekdays always there (S8A-AC-01). */
    private static SlaPolicy policy(SlaPolicyService.Policy policy) {
        SlaVersion version = policy.version();
        List<SlaTarget> targets = new ArrayList<>();
        for (Priority priority : Priority.values()) {
            SlaVersion.Goals goals = version.goals(priority);
            targets.add(new SlaTarget(priority, goals.firstResponse(), goals.resolution()));
        }
        List<SlaHours> hours = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            var span = version.hours().get(day);
            hours.add(span == null ? new SlaHours(day, null, null)
                    : new SlaHours(day, SlaPolicyService.time(span.opens()), SlaPolicyService.time(span.closes())));
        }
        SlaVersion.Changer by = version.changedBy();
        return new SlaPolicy(version.number(), targets, hours, version.holidays(), policy.timeZone(),
                ConsoleIssueController.utc(version.changedAt()), by == null ? null
                        : ConsoleIssueController.person(by.accountId(), by.name(), by.role(), by.photoUpdatedAt()));
    }
}
