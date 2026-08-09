package com.supplymind.provider.pboc;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fail-closed, PBOC-specific HTML parser; no guessed article ID or undocumented API is used. */
public final class PbocAnnouncementParser {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String PBOC_HOST = "www.pbc.gov.cn";
    private static final Pattern LINK_PATTERN = Pattern.compile(
            "(?is)<a\\b[^>]*?\\bhref\\s*=\\s*(['\\\"])(.*?)\\1[^>]*>(.*?)</a>");
    private static final Pattern H1_PATTERN = Pattern.compile("(?is)<h1\\b[^>]*>(.*?)</h1>");
    private static final Pattern TITLE_PATTERN = Pattern.compile("(?is)<title\\b[^>]*>(.*?)</title>");
    private static final Pattern TAG_PATTERN = Pattern.compile("(?is)<[^>]+>");
    private static final Pattern COMMENT_PATTERN = Pattern.compile("(?is)<!--.*?-->");
    private static final Pattern SCRIPT_STYLE_PATTERN = Pattern.compile("(?is)<(script|style)\\b.*?</\\1>");
    private static final Pattern CN_DATE_PATTERN = Pattern.compile("(20[0-9]{2})年\\s*([0-9]{1,2})月\\s*([0-9]{1,2})日");
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("(20[0-9]{2})-([0-9]{1,2})-([0-9]{1,2})");
    private static final Pattern PUBLISHED_PATTERN = Pattern.compile(
            "文章来源\\s*[：:]\\s*((?:20[0-9]{2})-[0-9]{1,2}-[0-9]{1,2}\\s+[0-9]{1,2}:[0-9]{2}:[0-9]{2})");
    private static final Pattern CHARSET_PATTERN = Pattern.compile("(?i)charset\\s*=\\s*['\\\"]?([^;\\s'\\\"]+)");

    public URI discoverLatestDetailUri(URI listUri, String listHtml) {
        requirePbocUri(listUri, "LIST");
        String html = requireNonBlank(listHtml, "PBOC announcement list is empty", listUri);
        Matcher matcher = LINK_PATTERN.matcher(html);
        List<LinkCandidate> candidates = new ArrayList<>();
        while (matcher.find()) {
            String title = visibleText(matcher.group(3));
            if (!title.contains("人民币汇率中间价公告")) {
                continue;
            }
            // The official list has undated navigation/history links with the same label.
            // Only a dated announcement link can establish a business-date candidate.
            if (findDates(title).isEmpty()) {
                continue;
            }
            LocalDate businessDate = exactlyOneConsistentDate(title, "announcement list title", listUri);
            URI detailUri = resolveDetailUri(listUri, htmlUnescape(matcher.group(2).trim()));
            candidates.add(new LinkCandidate(businessDate, detailUri));
        }
        if (candidates.isEmpty()) {
            throw rejected("LIST", listUri, "No PBOC middle-rate announcement link was discoverable from the official list");
        }
        LocalDate latestDate = candidates.stream().map(LinkCandidate::businessDate).max(Comparator.naturalOrder())
                .orElseThrow();
        List<LinkCandidate> latest = candidates.stream().filter(candidate -> candidate.businessDate().equals(latestDate)).toList();
        if (latest.size() != 1) {
            throw rejected("LIST", listUri, "PBOC announcement list has ambiguous latest business-date links");
        }
        return latest.get(0).detailUri();
    }

    public PbocAnnouncement parseDetail(URI detailUri, String detailHtml) {
        requirePbocUri(detailUri, "DETAIL");
        String html = requireNonBlank(detailHtml, "PBOC announcement detail is empty", detailUri);
        List<String> headings = findTagTexts(html, H1_PATTERN);
        String title;
        if (headings.size() == 1) {
            title = headings.get(0);
        } else {
            List<String> documentTitles = findTagTexts(html, TITLE_PATTERN);
            if (documentTitles.size() != 1) {
                throw rejected("DETAIL", detailUri, "PBOC announcement detail must expose exactly one title heading or document title");
            }
            title = documentTitles.get(0);
        }
        DateOccurrence titleDate = exactlyOneDateOccurrence(title, "announcement title", detailUri);

        String text = visibleText(html);
        int rateMarker = text.indexOf("中间价为");
        if (rateMarker < 0) {
            throw rejected("DETAIL", detailUri, "PBOC announcement detail has no middle-rate body marker");
        }
        int bodyStart = Math.max(0, rateMarker - 220);
        int bodyEnd = Math.min(text.length(), rateMarker + 220);
        LocalDate bodyDate = exactlyOneConsistentDate(text.substring(bodyStart, bodyEnd), "announcement body", detailUri);

        List<DateOccurrence> allDates = findDates(text);
        if (allDates.isEmpty()) {
            throw rejected("DETAIL", detailUri, "PBOC announcement detail has no closing business date");
        }
        LocalDate closingDate = allDates.get(allDates.size() - 1).date();
        if (!titleDate.date().equals(bodyDate) || !titleDate.date().equals(closingDate)) {
            throw rejected("DETAIL", detailUri, "PBOC title, body, and closing business dates are inconsistent");
        }

        String publishedAtRaw = extractPublishedAtRaw(text, detailUri);
        OffsetDateTime publishedAt = parsePublishedAt(publishedAtRaw, detailUri);
        String usd = exactlyOnePositiveDecimal(text, "1美元对人民币", detailUri);
        String eur = exactlyOnePositiveDecimal(text, "1欧元对人民币", detailUri);
        return new PbocAnnouncement(title, titleDate.raw(), titleDate.date(), publishedAtRaw, publishedAt, usd, eur);
    }

    public String decodeHtml(URI uri, byte[] entityBytes, String contentType) {
        if (entityBytes == null || entityBytes.length == 0) {
            throw rejected("HTTP", uri, "PBOC HTML entity is empty");
        }
        Charset charset = charsetFrom(contentType, uri);
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(entityBytes));
            return decoded.toString();
        } catch (CharacterCodingException exception) {
            throw new PbocCollectionException(PbocCollectionFailureKind.PARSE_REJECTED, "HTTP", uri, null,
                    "PBOC HTML entity cannot be decoded by its declared charset", exception);
        }
    }

    private static Charset charsetFrom(String contentType, URI uri) {
        if (contentType == null || contentType.isBlank()) {
            throw new PbocCollectionException(PbocCollectionFailureKind.CONTENT_TYPE_REJECTED, "HTTP", uri, null,
                    "PBOC response has no Content-Type");
        }
        Matcher matcher = CHARSET_PATTERN.matcher(contentType);
        if (!matcher.find()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(matcher.group(1));
        } catch (RuntimeException exception) {
            throw new PbocCollectionException(PbocCollectionFailureKind.CONTENT_TYPE_REJECTED, "HTTP", uri, null,
                    "PBOC response declares an unsupported charset", exception);
        }
    }

    private static URI resolveDetailUri(URI listUri, String href) {
        if (href == null || href.isBlank()) {
            throw rejected("LIST", listUri, "PBOC announcement link has no href");
        }
        final URI resolved;
        try {
            resolved = listUri.resolve(new URI(href));
        } catch (URISyntaxException exception) {
            throw new PbocCollectionException(PbocCollectionFailureKind.PARSE_REJECTED, "LIST", listUri, null,
                    "PBOC announcement link is not a valid URI", exception);
        }
        requirePbocUri(resolved, "LIST");
        return resolved;
    }

    private static void requirePbocUri(URI uri, String stage) {
        if (uri == null || !uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null || !PBOC_HOST.equalsIgnoreCase(uri.getHost()) || uri.getUserInfo() != null) {
            throw rejected(stage, uri, "PBOC requests must use anonymous public HTTPS on the official PBOC host");
        }
    }

    private static String requireNonBlank(String value, String message, URI uri) {
        if (value == null || value.isBlank()) {
            throw rejected("PARSE", uri, message);
        }
        return value;
    }

    private static List<String> findTagTexts(String html, Pattern pattern) {
        Matcher matcher = pattern.matcher(html);
        List<String> results = new ArrayList<>();
        while (matcher.find()) {
            String text = visibleText(matcher.group(1));
            if (!text.isBlank()) {
                results.add(text);
            }
        }
        return results;
    }

    private static DateOccurrence exactlyOneDateOccurrence(String text, String field, URI uri) {
        List<DateOccurrence> dates = findDates(text);
        if (dates.size() != 1) {
            throw rejected("DETAIL", uri, "PBOC " + field + " must contain exactly one business date");
        }
        return dates.get(0);
    }

    private static LocalDate exactlyOneConsistentDate(String text, String field, URI uri) {
        List<DateOccurrence> dates = findDates(text);
        if (dates.isEmpty()) {
            throw rejected("DETAIL", uri, "PBOC " + field + " has no business date");
        }
        LocalDate first = dates.get(0).date();
        if (dates.stream().anyMatch(date -> !date.date().equals(first))) {
            throw rejected("DETAIL", uri, "PBOC " + field + " has inconsistent business dates");
        }
        return first;
    }

    private static List<DateOccurrence> findDates(String text) {
        List<DateOccurrence> dates = new ArrayList<>();
        Matcher chinese = CN_DATE_PATTERN.matcher(text);
        while (chinese.find()) {
            dates.add(new DateOccurrence(chinese.group(), localDate(chinese.group(1), chinese.group(2), chinese.group(3))));
        }
        Matcher iso = ISO_DATE_PATTERN.matcher(text);
        while (iso.find()) {
            dates.add(new DateOccurrence(iso.group(), localDate(iso.group(1), iso.group(2), iso.group(3))));
        }
        dates.sort(Comparator.comparingInt(date -> text.indexOf(date.raw())));
        return dates;
    }

    private static LocalDate localDate(String year, String month, String day) {
        try {
            return LocalDate.of(Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid PBOC business date", exception);
        }
    }

    private static String extractPublishedAtRaw(String text, URI uri) {
        Matcher matcher = PUBLISHED_PATTERN.matcher(text);
        List<String> values = new ArrayList<>();
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        if (values.size() != 1) {
            throw rejected("DETAIL", uri, "PBOC announcement must contain exactly one 文章来源 timestamp");
        }
        return values.get(0);
    }

    private static OffsetDateTime parsePublishedAt(String raw, URI uri) {
        try {
            LocalDateTime dateTime = LocalDateTime.parse(raw,
                    DateTimeFormatter.ofPattern("uuuu-M-d HH:mm:ss", Locale.ROOT));
            return dateTime.atZone(SHANGHAI).toOffsetDateTime();
        } catch (DateTimeParseException exception) {
            throw new PbocCollectionException(PbocCollectionFailureKind.PARSE_REJECTED, "DETAIL", uri, null,
                    "PBOC 文章来源 timestamp is not a valid Asia/Shanghai local datetime", exception);
        }
    }

    private static String exactlyOnePositiveDecimal(String text, String anchor, URI uri) {
        Pattern valuePattern = Pattern.compile(Pattern.quote(anchor) + "\\s*([0-9]+(?:\\.[0-9]+)?)\\s*元");
        Matcher matcher = valuePattern.matcher(text);
        List<String> values = new ArrayList<>();
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        if (values.size() != 1) {
            throw rejected("DETAIL", uri, "PBOC anchor " + anchor + " must map to exactly one decimal value");
        }
        String value = values.get(0);
        try {
            if (new java.math.BigDecimal(value).signum() <= 0) {
                throw rejected("DETAIL", uri, "PBOC anchor " + anchor + " must map to a positive decimal value");
            }
        } catch (NumberFormatException exception) {
            throw new PbocCollectionException(PbocCollectionFailureKind.PARSE_REJECTED, "DETAIL", uri, null,
                    "PBOC anchor " + anchor + " has an invalid decimal value", exception);
        }
        return value;
    }

    private static String visibleText(String fragment) {
        String withoutComments = COMMENT_PATTERN.matcher(fragment).replaceAll(" ");
        String withoutScripts = SCRIPT_STYLE_PATTERN.matcher(withoutComments).replaceAll(" ");
        String withoutTags = TAG_PATTERN.matcher(withoutScripts).replaceAll(" ");
        return htmlUnescape(withoutTags).replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String htmlUnescape(String value) {
        String basic = value.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");
        Matcher matcher = Pattern.compile("&#(x[0-9A-Fa-f]+|[0-9]+);").matcher(basic);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group(1);
            int codePoint = token.startsWith("x") || token.startsWith("X")
                    ? Integer.parseInt(token.substring(1), 16) : Integer.parseInt(token, 10);
            matcher.appendReplacement(output, Matcher.quoteReplacement(new String(Character.toChars(codePoint))));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static PbocCollectionException rejected(String stage, URI uri, String message) {
        return new PbocCollectionException(PbocCollectionFailureKind.PARSE_REJECTED, stage, uri, null, message);
    }

    private record LinkCandidate(LocalDate businessDate, URI detailUri) { }
    private record DateOccurrence(String raw, LocalDate date) { }
}