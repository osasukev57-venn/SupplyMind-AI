package com.supplymind.foundation.codec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.supplymind.foundation.model.SchemaValidationException;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** JSON v1 file codec: compact schema order, UTF-8/no-BOM, LF and exactly one trailing newline. */
public final class JsonV1Codec {
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.WRITE_DATE_KEYS_AS_TIMESTAMPS)
            .disable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .serializationInclusion(JsonInclude.Include.ALWAYS)
            .build();

    private JsonV1Codec() {
    }

    /** Exposed for storage code that needs the exact same configured mapper for schema checks. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String encodeCompact(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new SchemaValidationException("Unable to encode JSON v1: " + exception.getOriginalMessage());
        }
    }

    public static byte[] encodeFile(Object value) {
        return (encodeCompact(value) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    public static <T> T decodeFile(byte[] utf8Bytes, Class<T> type) {
        String content = decodeStrictFileText(utf8Bytes);
        return decodeCompact(content.substring(0, content.length() - 1), type);
    }

    public static <T> T decodeCompact(String compactJson, Class<T> type) {
        try {
            return MAPPER.readValue(compactJson, type);
        } catch (JsonProcessingException exception) {
            throw new SchemaValidationException("Invalid JSON v1: " + exception.getOriginalMessage());
        }
    }

    public static <T> List<T> decodeCompactList(String compactJson, Class<T> elementType) {
        try {
            JavaType type = MAPPER.getTypeFactory().constructCollectionType(List.class, elementType);
            return List.copyOf(MAPPER.readValue(compactJson, type));
        } catch (JsonProcessingException exception) {
            throw new SchemaValidationException("Invalid compact JSON v1 array: " + exception.getOriginalMessage());
        }
    }

    public static String sha256LowerHex(byte[] bytes) {
        if (bytes == null) {
            throw new SchemaValidationException("Bytes to hash are required");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available in the Java runtime", exception);
        }
    }

    private static String decodeStrictFileText(byte[] utf8Bytes) {
        if (utf8Bytes == null || utf8Bytes.length == 0) {
            throw new SchemaValidationException("JSON v1 file must not be empty");
        }
        if (utf8Bytes.length >= 3 && (utf8Bytes[0] & 0xff) == 0xef && (utf8Bytes[1] & 0xff) == 0xbb
                && (utf8Bytes[2] & 0xff) == 0xbf) {
            throw new SchemaValidationException("JSON v1 file must not include a UTF-8 BOM");
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        final String content;
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(utf8Bytes));
            content = decoded.toString();
        } catch (CharacterCodingException exception) {
            throw new SchemaValidationException("JSON v1 file must be valid UTF-8");
        }
        if (content.indexOf('\r') >= 0 || !content.endsWith("\n") || content.endsWith("\n\n")) {
            throw new SchemaValidationException("JSON v1 file must use LF and end with exactly one newline");
        }
        return content;
    }
}
