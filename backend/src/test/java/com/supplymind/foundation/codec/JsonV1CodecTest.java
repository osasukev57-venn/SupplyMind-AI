package com.supplymind.foundation.codec;

import com.supplymind.foundation.model.DomainFixtures;
import com.supplymind.foundation.model.MonitorSeriesDefaults;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.SchemaValidationException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonV1CodecTest {
    @Test
    void emitsStableUtf8NoBomLfAndExplicitNulls() {
        byte[] first = JsonV1Codec.encodeFile(MonitorSeriesDefaults.initialPboc(
                OffsetDateTime.parse("2026-08-08T10:00:00+08:00")));
        byte[] second = JsonV1Codec.encodeFile(MonitorSeriesDefaults.initialPboc(
                OffsetDateTime.parse("2026-08-08T10:00:00+08:00")));
        String text = new String(first, StandardCharsets.UTF_8);

        assertArrayEquals(first, second);
        assertFalse(first.length >= 3 && first[0] == (byte) 0xef && first[1] == (byte) 0xbb && first[2] == (byte) 0xbf);
        assertTrue(text.endsWith("\n"));
        assertFalse(text.contains("\r"));
        assertTrue(text.contains("\"fallbackReason\":null"));
    }

    @Test
    void rejectsBomOrMoreThanOneTrailingNewline() {
        byte[] valid = JsonV1Codec.encodeFile(DomainFixtures.rawReceipt());
        byte[] bom = new byte[valid.length + 3];
        bom[0] = (byte) 0xef;
        bom[1] = (byte) 0xbb;
        bom[2] = (byte) 0xbf;
        System.arraycopy(valid, 0, bom, 3, valid.length);

        assertThrows(SchemaValidationException.class, () -> JsonV1Codec.decodeFile(bom, RawReceiptV1.class));
        assertThrows(SchemaValidationException.class, () -> JsonV1Codec.decodeFile(
                (new String(valid, StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8), RawReceiptV1.class));
    }
}
