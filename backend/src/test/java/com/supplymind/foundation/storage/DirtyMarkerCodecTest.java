package com.supplymind.foundation.storage;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DirtyMarkerCodecTest {

    private final DirtyMarkerCodec codec = new DirtyMarkerCodec();

    @Test
    void acceptsOnlyStrictUtf8LfTerminatedJsonV1MarkerBytes() {
        DirtyMarkerV1 marker = DirtyMarkerV1.open(
                "dirty-codec-1",
                DirtyTransactionType.SINGLE_FILE,
                OffsetDateTime.parse("2026-08-08T10:00:00+08:00"),
                List.of(new DirtyTargetV1(1, DirtyTargetRole.BUSINESS_FILE, "staging/run-1.json",
                        "staging/run-1.json.manifest.json", "a".repeat(64), null, DirtyTargetPhase.PREPARED)));
        byte[] canonical = codec.encode(marker);

        assertEquals(marker, codec.decode(canonical));
        assertThrows(StorageException.class, () -> codec.decode(new byte[] {(byte) 0xc3, 0x28}),
                "malformed UTF-8 must never be silently replaced");
        assertThrows(StorageException.class, () -> codec.decode(
                new String(canonical, StandardCharsets.UTF_8).replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8)),
                "DirtyMarker files are JSON v1 with LF, never CRLF");
    }
}
