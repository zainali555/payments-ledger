package com.zainali.ledger.ledger;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zainali.ledger.account.AccountAccess;
import com.zainali.ledger.common.Caller;

@Service
class HistoryService {

	private final LedgerEntryRepository entries;
	private final AccountAccess access;

	HistoryService(LedgerEntryRepository entries, AccountAccess access) {
		this.entries = entries;
		this.access = access;
	}

	/**
	 * One page of an account's entries. {@code from} is inclusive and {@code to} is exclusive; either may be null. A
	 * page past the end is empty with the real totals.
	 */
	@Transactional(readOnly = true)
	HistoryPage history(Caller caller, long accountId, Instant from, Instant to, HistorySort sort, int page, int size) {
		access.requireVisible(caller, accountId);
		Specification<LedgerEntry> spec = (root, query, cb) -> cb.equal(root.get("accountId"), accountId);
		if (from != null) {
			spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.<Instant>get("createdAt"), from));
		}
		if (to != null) {
			spec = spec.and((root, query, cb) -> cb.lessThan(root.<Instant>get("createdAt"), to));
		}
		// The offset must fit in an int (Hibernate's first-result type); beyond that the page is empty by definition,
		// because no account has two billion entries.
		if ((long) page * size > Integer.MAX_VALUE) {
			long total = entries.count(spec);
			return new HistoryPage(List.of(), page, size, total, totalPages(total, size));
		}
		Page<LedgerEntry> result = entries.findAll(spec, PageRequest.of(page, size, sort.toSort()));
		return new HistoryPage(result.getContent().stream().map(EntryResponse::from).toList(), page, size,
				result.getTotalElements(), result.getTotalPages());
	}

	private static int totalPages(long total, int size) {
		return (int) ((total + size - 1) / size);
	}

}
