package com.supplymind.foundation.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The dedicated DirtyMarkerV1 bootstrap recovery algorithm. Only the legal
 * same-transaction marker candidate group may be adopted without a canonical
 * marker. Ordinary .tmp/.bak files are deliberately outside this class.
 */
public final class DirtyMarkerRecovery {

    private static final Pattern CANONICAL_PATTERN = Pattern.compile("(?<id>[A-Za-z0-9._-]+)\\.json");
    private static final Pattern TEMPORARY_PATTERN = Pattern.compile("\\.(?<id>[A-Za-z0-9._-]+)\\.json\\.marker\\.tmp");
    private static final Pattern BACKUP_PATTERN = Pattern.compile("\\.(?<id>[A-Za-z0-9._-]+)\\.json\\.marker\\.bak");

    private final DirtyMarkerCodec codec;

    public DirtyMarkerRecovery(DirtyMarkerCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /**
     * Restores the highest valid marker revision to the canonical path and
     * returns the canonical marker(s) for business-target recovery. Verified
     * marker tmp/bak candidates are deliberately retained: only successful
     * business recovery may clean them after final verification.
     */
    public List<DirtyMarkerV1> recoverCanonicalMarkers(DataRoot dataRoot) {
        Objects.requireNonNull(dataRoot, "dataRoot");
        Path dirtyDirectory = dataRoot.resolveDataRef("runtime/dirty/bootstrap.json").getParent();
        // bootstrap.json is only used to resolve the frozen directory shape; it is never created.
        try {
            Files.createDirectories(dirtyDirectory);
        } catch (IOException exception) {
            throw new StorageException("Cannot create dirty marker directory " + dirtyDirectory, exception);
        }

        Map<String, List<CandidatePath>> groups = new HashMap<>();
        try (var paths = Files.list(dirtyDirectory)) {
            paths.filter(Files::isRegularFile).forEach(path -> addIfMarkerCandidate(groups, path));
        } catch (IOException exception) {
            throw new StorageException("Unable to inspect dirty marker directory " + dirtyDirectory, exception);
        }

        List<DirtyMarkerV1> recovered = new ArrayList<>();
        for (String transactionId : groups.keySet().stream().sorted().toList()) {
            recovered.add(recoverGroup(dataRoot, transactionId, groups.get(transactionId)));
        }
        return List.copyOf(recovered);
    }

    private void addIfMarkerCandidate(Map<String, List<CandidatePath>> groups, Path path) {
        String filename = path.getFileName().toString();
        CandidateKind kind = CandidateKind.from(filename);
        if (kind == null) {
            return;
        }
        String transactionId = kind.extractTransactionId(filename);
        groups.computeIfAbsent(transactionId, ignored -> new ArrayList<>()).add(new CandidatePath(path, kind));
    }

    private DirtyMarkerV1 recoverGroup(DataRoot dataRoot, String transactionId, List<CandidatePath> paths) {
        List<Candidate> candidates = paths.stream().map(path -> readCandidate(transactionId, path)).toList();
        Map<Long, Candidate> byRevision = new HashMap<>();
        for (Candidate candidate : candidates) {
            Candidate existing = byRevision.get(candidate.marker().markerRevision());
            if (existing != null && !Arrays.equals(existing.bytes(), candidate.bytes())) {
                throw failClosed(transactionId, "Multiple marker candidates have different bytes at revision "
                        + candidate.marker().markerRevision(), paths);
            }
            if (existing == null || candidate.kind().selectionPriority() < existing.kind().selectionPriority()) {
                byRevision.put(candidate.marker().markerRevision(), candidate);
            }
        }

        List<Candidate> revisions = byRevision.values().stream()
                .sorted(Comparator.comparingLong(candidate -> candidate.marker().markerRevision()))
                .toList();
        for (int index = 1; index < revisions.size(); index++) {
            Candidate previous = revisions.get(index - 1);
            Candidate next = revisions.get(index);
            if (!next.marker().isDirectLegalSuccessorOf(previous.marker())) {
                throw failClosed(transactionId, "Marker revisions are not contiguous monotonic transitions", paths);
            }
        }

        Candidate highest = revisions.get(revisions.size() - 1);
        restoreCanonical(dataRoot, transactionId, highest, candidates);
        return highest.marker();
    }

    private Candidate readCandidate(String transactionId, CandidatePath candidatePath) {
        try {
            byte[] bytes = Files.readAllBytes(candidatePath.path());
            DirtyMarkerV1 marker = codec.decode(bytes);
            if (!transactionId.equals(marker.transactionId())
                    || !candidatePath.kind().matchesFilename(marker.transactionId(), candidatePath.path().getFileName().toString())) {
                throw new StorageException("Marker filename does not match transactionId");
            }
            return new Candidate(candidatePath.path(), candidatePath.kind(), marker, bytes);
        } catch (IOException | StorageException exception) {
            throw failClosed(transactionId, "Invalid DirtyMarker candidate " + candidatePath.path().getFileName(),
                    List.of(candidatePath), exception);
        }
    }

    private void restoreCanonical(DataRoot root, String transactionId, Candidate highest, List<Candidate> candidates) {
        Path canonical = root.resolveDataRef(DataPaths.dirtyMarkerRef(transactionId));
        Path temporary = root.resolveInternalRelative(DataPaths.dirtyMarkerTemporaryRef(transactionId));
        Path backup = root.resolveInternalRelative(DataPaths.dirtyMarkerBackupRef(transactionId));

        if (highest.kind() == CandidateKind.BACKUP) {
            if (!FileDigest.bytesEqual(backup, highest.bytes())) {
                throw failClosed(transactionId, "Highest DirtyMarker backup bytes changed during recovery", candidates);
            }
            if (Files.exists(temporary)) {
                Candidate lowerTemporary = verifiedCandidate(transactionId, temporary, candidates);
                if (lowerTemporary.marker().markerRevision() >= highest.marker().markerRevision()) {
                    throw failClosed(transactionId, "Highest DirtyMarker backup cannot replace a non-lower marker tmp", candidates);
                }
                deleteVerifiedCandidate(temporary);
            }
            // The high revision is forced into the fixed marker tmp before the canonical swap.
            FileDigest.writeCreateNewAndForce(temporary, highest.bytes());
        } else if (highest.kind() == CandidateKind.TEMPORARY) {
            if (!FileDigest.bytesEqual(temporary, highest.bytes())) {
                throw failClosed(transactionId, "Highest DirtyMarker tmp bytes changed during recovery", candidates);
            }
        }
        if (highest.kind() == CandidateKind.CANONICAL) {
            return;
        }
        if (Files.exists(canonical)) {
            if (Files.exists(backup)) {
                verifiedCandidate(transactionId, backup, candidates);
                // The highest bytes are already forced in marker.tmp, so this
                // verified old candidate may now be replaced by canonical.
                deleteVerifiedCandidate(backup);
            }
            AtomicMoveSupport.moveToEmptyTarget(canonical, backup);
        }
        AtomicMoveSupport.moveToEmptyTarget(temporary, canonical);
        // Do not delete verified marker candidates here. AtomicFileRecovery
        // removes canonical/tmp/bak only after every business data+manifest
        // target has passed final verification.
    }

    private Candidate verifiedCandidate(String transactionId, Path path, List<Candidate> candidates) {
        return candidates.stream().filter(candidate -> candidate.path().equals(path)).findFirst()
                .orElseThrow(() -> failClosed(transactionId, "Marker candidate changed after validation: " + path, candidates));
    }

    private void deleteVerifiedCandidate(Path path) {
        try {
            Files.delete(path);
        } catch (IOException exception) {
            throw new StorageException("Unable to clear a verified marker candidate required by atomic recovery: " + path,
                    exception);
        }
    }

    private StorageException failClosed(String transactionId, String reason, List<?> evidence) {
        return new StorageException("DirtyMarker recovery fail-closed for transaction " + transactionId + ": " + reason
                + "; evidence preserved=" + evidence);
    }

    private StorageException failClosed(String transactionId, String reason, List<?> evidence, Throwable cause) {
        return new StorageException("DirtyMarker recovery fail-closed for transaction " + transactionId + ": " + reason
                + "; evidence preserved=" + evidence, cause);
    }

    private enum CandidateKind {
        CANONICAL(CANONICAL_PATTERN),
        TEMPORARY(TEMPORARY_PATTERN),
        BACKUP(BACKUP_PATTERN);

        private final Pattern pattern;

        CandidateKind(Pattern pattern) {
            this.pattern = pattern;
        }

        static CandidateKind from(String filename) {
            for (CandidateKind kind : values()) {
                if (kind.pattern.matcher(filename).matches()) {
                    return kind;
                }
            }
            return null;
        }

        String extractTransactionId(String filename) {
            Matcher matcher = pattern.matcher(filename);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("Not a marker candidate filename: " + filename);
            }
            return matcher.group("id");
        }

        boolean matchesFilename(String transactionId, String filename) {
            return switch (this) {
                case CANONICAL -> filename.equals(transactionId + ".json");
                case TEMPORARY -> filename.equals("." + transactionId + ".json.marker.tmp");
                case BACKUP -> filename.equals("." + transactionId + ".json.marker.bak");
            };
        }

        int selectionPriority() {
            return switch (this) {
                case CANONICAL -> 0;
                case TEMPORARY -> 1;
                case BACKUP -> 2;
            };
        }
    }

    private record CandidatePath(Path path, CandidateKind kind) {
    }

    private record Candidate(Path path, CandidateKind kind, DirtyMarkerV1 marker, byte[] bytes) {
    }
}
