package com.zainali.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import com.zainali.ledger.common.ErrorCode;
import com.zainali.ledger.common.LedgerException;

class HistorySortTests {

	@Test
	void defaultsToNewestFirst() {
		assertThat(HistorySort.parse(null)).isEqualTo(new HistorySort("createdAt", Sort.Direction.DESC));
	}

	@Test
	void fieldAloneSortsAscending() {
		assertThat(HistorySort.parse("amount")).isEqualTo(new HistorySort("amount", Sort.Direction.ASC));
	}

	@Test
	void parsesFieldAndDirectionIgnoringCase() {
		assertThat(HistorySort.parse("CreatedAt,DESC")).isEqualTo(new HistorySort("createdAt", Sort.Direction.DESC));
		assertThat(HistorySort.parse("amount, asc")).isEqualTo(new HistorySort("amount", Sort.Direction.ASC));
	}

	@Test
	void idTiebreakerFollowsTheSortDirection() {
		Sort sort = HistorySort.parse("amount,desc").toSort();

		assertThat(sort.stream().map(order -> order.getProperty() + ":" + order.getDirection()).toList())
				.containsExactly("amount:DESC", "id:DESC");
	}

	@Test
	void rejectsUnknownFieldsDirectionsAndShapes() {
		for (String bad : new String[] { "", "balance", "amount,up", "amount,asc,x", ",asc", "id", "amount;drop table" }) {
			assertThatThrownBy(() -> HistorySort.parse(bad))
					.as(bad)
					.isInstanceOfSatisfying(LedgerException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
		}
	}

}
