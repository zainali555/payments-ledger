package com.zainali.ledger.ledger;

import java.util.Locale;
import java.util.Map;

import org.springframework.data.domain.Sort;

import com.zainali.ledger.common.ErrorCode;
import com.zainali.ledger.common.LedgerException;

/**
 * The {@code sort} query parameter: {@code field[,direction]}, with field {@code createdAt} or {@code amount} and
 * direction {@code asc} or {@code desc}. The default is newest first. The entry id is always the final tiebreaker, in
 * the same direction, so pages are stable when sort keys are equal.
 */
record HistorySort(String field, Sort.Direction direction) {

	static final HistorySort DEFAULT = new HistorySort("createdAt", Sort.Direction.DESC);

	private static final Map<String, String> FIELDS = Map.of("createdat", "createdAt", "amount", "amount");

	static HistorySort parse(String value) {
		if (value == null) {
			return DEFAULT;
		}
		String[] parts = value.split(",", -1);
		String field = FIELDS.get(parts[0].trim().toLowerCase(Locale.ROOT));
		if (parts.length > 2 || field == null) {
			throw invalid();
		}
		if (parts.length == 1) {
			return new HistorySort(field, Sort.Direction.ASC);
		}
		return switch (parts[1].trim().toLowerCase(Locale.ROOT)) {
			case "asc" -> new HistorySort(field, Sort.Direction.ASC);
			case "desc" -> new HistorySort(field, Sort.Direction.DESC);
			default -> throw invalid();
		};
	}

	Sort toSort() {
		return Sort.by(new Sort.Order(direction, field), new Sort.Order(direction, "id"));
	}

	private static LedgerException invalid() {
		return new LedgerException(ErrorCode.VALIDATION_FAILED,
				"sort must be createdAt or amount, optionally followed by ,asc or ,desc");
	}

}
