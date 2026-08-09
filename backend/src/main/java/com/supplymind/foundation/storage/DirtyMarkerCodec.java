package com.supplymind.foundation.storage;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Strict JSON v1 codec for DirtyMarkerV1 bootstrap recovery. */
public final class DirtyMarkerCodec {

    private final ObjectMapper mapper;

    public DirtyMarkerCodec() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(SerializationFeature.INDENT_OUTPUT);
        mapper.disable(JsonGenerator.Feature.ESCAPE_NON_ASCII);
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mapper.disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
    }

    public byte[] encode(DirtyMarkerV1 marker) {
        try {
            return (mapper.writeValueAsString(Objects.requireNonNull(marker, "marker")) + "\n")
                    .getBytes(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new StorageException("Unable to encode DirtyMarkerV1", exception);
        }
    }

    public DirtyMarkerV1 decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new StorageException("DirtyMarkerV1 JSON must not be empty");
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            throw new StorageException("DirtyMarkerV1 JSON must not contain a UTF-8 BOM");
        }
        final String json;
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
            json = decoded.toString();
        } catch (CharacterCodingException exception) {
            throw new StorageException("DirtyMarkerV1 JSON must be valid UTF-8", exception);
        }
        if (json.indexOf('\r') >= 0 || !json.endsWith("\n") || json.endsWith("\n\n")) {
            throw new StorageException("DirtyMarkerV1 JSON must use LF and end with exactly one newline");
        }
        try {
            return mapper.readValue(json, DirtyMarkerV1.class);
        } catch (IOException | IllegalArgumentException exception) {
            throw new StorageException("Invalid DirtyMarkerV1 JSON", exception);
        }
    }
}
