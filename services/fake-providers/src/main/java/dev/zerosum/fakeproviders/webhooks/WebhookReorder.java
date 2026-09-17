package dev.zerosum.fakeproviders.webhooks;

import dev.zerosum.fakeproviders.faults.Decision;
import dev.zerosum.fakeproviders.faults.FaultProfiles;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Webhook reorder simulation, kept in one class on purpose (D05-2).
 *
 * <p>This is the minimum-cut line ([docs/scope-decisions.md](../../../../../../../../docs/scope-decisions.md) lists
 * reorder as S05's cut): deleting this component and its call in {@link WebhookSender} removes reorder simulation
 * without touching duplicate delivery, drops or redelivery.
 *
 * <p>Two shapes of reorder, because a provider produces both. When two events for the same object are due together,
 * the later one is delivered first. When only one is due, it is held back so that a <em>future</em> event for the
 * same object overtakes it — which is how a real out-of-order delivery actually happens, since the second event does
 * not exist yet when the first is sent.
 *
 * <p>An event is held at most once ({@code reorder_held}), and never while a sibling is already held, so every event
 * still makes progress and a pair cannot stall each other into never being delivered.
 */
@Component
class WebhookReorder {

    private final FaultProfiles profiles;

    WebhookReorder(FaultProfiles profiles) {
        this.profiles = profiles;
    }

    /** What to do with one due batch: deliver these now, hold those back. */
    record Plan(List<PendingEvent> deliver, List<PendingEvent> hold) {
    }

    Plan plan(List<PendingEvent> due) {
        Map<String, List<PendingEvent>> byObject = new LinkedHashMap<>();
        due.forEach(event -> byObject.computeIfAbsent(event.providerRef(), key -> new ArrayList<>()).add(event));

        List<PendingEvent> deliver = new ArrayList<>();
        List<PendingEvent> hold = new ArrayList<>();
        for (List<PendingEvent> group : byObject.values()) {
            PendingEvent first = group.getFirst();
            if (!profiles.fires(first.provider(), Decision.WEBHOOK_REORDER, first.eventId())) {
                deliver.addAll(group);
            } else if (group.size() >= 2) {
                deliver.add(group.get(1));
                deliver.add(group.getFirst());
                deliver.addAll(group.subList(2, group.size()));
            } else if (!first.reorderHeld() && !first.siblingHeld()) {
                hold.add(first);
            } else {
                deliver.add(first);
            }
        }
        return new Plan(deliver, hold);
    }
}
