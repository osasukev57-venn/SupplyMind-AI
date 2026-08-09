package com.supplymind.provider.pboc;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Parsed fields from one PBOC announcement; raw HTTP bytes remain outside this projection. */
record PbocAnnouncement(String title, String titleBusinessDateRaw, LocalDate businessDate, String sourcePublishedAtRaw,
                        OffsetDateTime sourcePublishedAt, String usdRawValue, String eurRawValue) {
}