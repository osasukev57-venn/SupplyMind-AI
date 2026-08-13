package com.supplymind.provider;

/**
 * M3 history-acquisition contract: whether a provider collect request targets the current
 * business day ({@link #CURRENT}) or an explicit historical business-date range
 * ({@link #HISTORY}). A HISTORY request must always carry historyStartDate/historyEndDate so
 * the provider knows exactly which dates/range to collect; a CURRENT request must not invent
 * implicit internal dates.
 */
public enum CollectionMode {
    CURRENT,
    HISTORY
}
