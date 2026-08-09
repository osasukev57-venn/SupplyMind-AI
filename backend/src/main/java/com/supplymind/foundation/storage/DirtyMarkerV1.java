package com.supplymind.foundation.storage;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A recoverable transaction journal. It is intentionally not a lifecycle
 * record: its states govern disk atomicity only.
 */
@JsonPropertyOrder({
        "schemaVersion", "transactionId", "transactionType", "createdAt", "markerRevision", "transactionPhase", "targets"
})
public record DirtyMarkerV1(
        String schemaVersion,
        String transactionId,
        DirtyTransactionType transactionType,
        OffsetDateTime createdAt,
        long markerRevision,
        DirtyTransactionPhase transactionPhase,
        List<DirtyTargetV1> targets
) {

    public static final String SCHEMA_VERSION = "1.0";

    public DirtyMarkerV1 {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new StorageException("DirtyMarkerV1 schemaVersion must be \"1.0\"");
        }
        DataPaths.requireIdentifier(transactionId, "transactionId");
        Objects.requireNonNull(transactionType, "transactionType");
        Objects.requireNonNull(createdAt, "createdAt");
        if (markerRevision < 1) {
            throw new StorageException("DirtyMarker markerRevision must be positive");
        }
        Objects.requireNonNull(transactionPhase, "transactionPhase");
        if (targets == null || targets.isEmpty()) {
            throw new StorageException("DirtyMarker must have at least one target");
        }
        targets = List.copyOf(targets);
        validateTargetShape(transactionType, targets);
        if (transactionPhase == DirtyTransactionPhase.COMMITTED
                && targets.stream().anyMatch(target -> target.targetPhase() != DirtyTargetPhase.MANIFEST_COMMITTED)) {
            throw new StorageException("A COMMITTED DirtyMarker requires every target manifest to be committed");
        }
        validateRevisionMatchesState(markerRevision, transactionPhase, targets);
    }

    public static DirtyMarkerV1 open(String transactionId, DirtyTransactionType type, OffsetDateTime createdAt,
                                      List<DirtyTargetV1> targets) {
        return new DirtyMarkerV1(SCHEMA_VERSION, transactionId, type, createdAt, 1,
                DirtyTransactionPhase.OPEN, targets);
    }

    public DirtyMarkerV1 advanceTarget(int order, DirtyTargetPhase nextPhase) {
        if (transactionPhase != DirtyTransactionPhase.OPEN) {
            throw new StorageException("A committed DirtyMarker cannot be advanced");
        }
        boolean found = false;
        List<DirtyTargetV1> nextTargets = new ArrayList<>(targets.size());
        for (DirtyTargetV1 target : targets) {
            if (target.order() == order) {
                nextTargets.add(target.advanceTo(nextPhase));
                found = true;
            } else {
                nextTargets.add(target);
            }
        }
        if (!found) {
            throw new StorageException("DirtyMarker has no target order " + order);
        }
        return new DirtyMarkerV1(schemaVersion, transactionId, transactionType, createdAt, markerRevision + 1,
                DirtyTransactionPhase.OPEN, nextTargets);
    }

    public DirtyMarkerV1 commit() {
        if (transactionPhase != DirtyTransactionPhase.OPEN) {
            throw new StorageException("DirtyMarker transaction is already committed");
        }
        if (targets.stream().anyMatch(target -> target.targetPhase() != DirtyTargetPhase.MANIFEST_COMMITTED)) {
            throw new StorageException("All target manifests must be committed before transaction commit");
        }
        return new DirtyMarkerV1(schemaVersion, transactionId, transactionType, createdAt, markerRevision + 1,
                DirtyTransactionPhase.COMMITTED, targets);
    }

    /** Validates the immutable-identity and monotonic transition rules between persisted revisions. */
    public boolean isDirectLegalSuccessorOf(DirtyMarkerV1 previous) {
        if (previous == null || markerRevision != previous.markerRevision + 1
                || !hasSameImmutableFields(previous)) {
            return false;
        }
        if (previous.transactionPhase == DirtyTransactionPhase.COMMITTED
                || transactionPhase.ordinal() < previous.transactionPhase.ordinal()) {
            return false;
        }
        boolean transactionChanged = transactionPhase != previous.transactionPhase;
        int targetTransitions = 0;
        for (int index = 0; index < targets.size(); index++) {
            DirtyTargetPhase oldPhase = previous.targets.get(index).targetPhase();
            DirtyTargetPhase newPhase = targets.get(index).targetPhase();
            if (newPhase.ordinal() < oldPhase.ordinal() || newPhase.ordinal() - oldPhase.ordinal() > 1) {
                return false;
            }
            if (newPhase != oldPhase) {
                targetTransitions++;
            }
        }
        if (transactionChanged) {
            return transactionPhase == DirtyTransactionPhase.COMMITTED && targetTransitions == 0;
        }
        return targetTransitions == 1;
    }

    public boolean hasSameImmutableFields(DirtyMarkerV1 other) {
        if (other == null || !schemaVersion.equals(other.schemaVersion) || !transactionId.equals(other.transactionId)
                || transactionType != other.transactionType || !createdAt.equals(other.createdAt)
                || targets.size() != other.targets.size()) {
            return false;
        }
        for (int index = 0; index < targets.size(); index++) {
            if (!targets.get(index).hasSameImmutableFields(other.targets.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static void validateTargetShape(DirtyTransactionType type, List<DirtyTargetV1> targets) {
        List<DirtyTargetV1> ordered = targets.stream().sorted(Comparator.comparingInt(DirtyTargetV1::order)).toList();
        if (!ordered.equals(targets)) {
            throw new StorageException("DirtyMarker targets must be ordered by order");
        }
        for (int index = 0; index < targets.size(); index++) {
            if (targets.get(index).order() != index + 1) {
                throw new StorageException("DirtyMarker target orders must be continuous starting at 1");
            }
        }
        switch (type) {
            case SINGLE_FILE -> {
                if (targets.size() != 1 || targets.get(0).role() != DirtyTargetRole.BUSINESS_FILE) {
                    throw new StorageException("SINGLE_FILE requires exactly one BUSINESS_FILE target");
                }
            }
            case CONFIG_ACTIVATION -> {
                if (targets.size() != 2 || targets.get(0).role() != DirtyTargetRole.CONFIG_HISTORY
                        || targets.get(1).role() != DirtyTargetRole.CONFIG_ACTIVE) {
                    throw new StorageException("CONFIG_ACTIVATION requires CONFIG_HISTORY order 1 and CONFIG_ACTIVE order 2");
                }
                if (targets.get(1).targetPhase() != DirtyTargetPhase.PREPARED
                        && targets.get(0).targetPhase() != DirtyTargetPhase.MANIFEST_COMMITTED) {
                    throw new StorageException("CONFIG_ACTIVATION cannot advance CONFIG_ACTIVE before CONFIG_HISTORY manifest");
                }
            }
            case AGGREGATION_BATCH -> {
                if (targets.stream().anyMatch(target -> target.role() != DirtyTargetRole.BUSINESS_FILE)) {
                    throw new StorageException("AGGREGATION_BATCH targets must be BUSINESS_FILE targets");
                }
                List<String> refs = targets.stream().map(DirtyTargetV1::dataRef).toList();
                if (!refs.stream().sorted().toList().equals(refs)) {
                    throw new StorageException("AGGREGATION_BATCH targets must be ordered by dataRef");
                }
            }
        }
    }

    private static void validateRevisionMatchesState(
            long markerRevision,
            DirtyTransactionPhase transactionPhase,
            List<DirtyTargetV1> targets
    ) {
        long expected = 1;
        for (DirtyTargetV1 target : targets) {
            expected = Math.addExact(expected, switch (target.targetPhase()) {
                case PREPARED -> 0;
                case DATA_COMMITTED -> 1;
                case MANIFEST_COMMITTED -> 2;
            });
        }
        if (transactionPhase == DirtyTransactionPhase.COMMITTED) {
            expected = Math.incrementExact(expected);
        }
        if (markerRevision != expected) {
            throw new StorageException("DirtyMarker markerRevision must equal the exact number of legal state advances: "
                    + expected + " expected but was " + markerRevision);
        }
    }
}
