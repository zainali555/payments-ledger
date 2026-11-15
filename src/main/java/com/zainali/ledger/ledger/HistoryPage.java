package com.zainali.ledger.ledger;

import java.util.List;

record HistoryPage(List<EntryResponse> content, int page, int size, long totalElements, int totalPages) {
}
