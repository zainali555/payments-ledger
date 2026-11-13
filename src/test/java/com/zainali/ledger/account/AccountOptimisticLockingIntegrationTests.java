package com.zainali.ledger.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.zainali.ledger.TestcontainersConfiguration;
import com.zainali.ledger.common.Currency;

/**
 * The {@code @Version} column is the safety net behind the row lock (DECISIONS 8): if some code path ever updates an
 * account without {@code SELECT ... FOR UPDATE}, a stale write must fail and not silently overwrite a newer balance.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AccountOptimisticLockingIntegrationTests {

	@Autowired
	AccountRepository accounts;

	@Autowired
	PlatformTransactionManager transactionManager;

	private TransactionTemplate tx(int propagation) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setPropagationBehavior(propagation);
		return template;
	}

	private long newAccount() {
		return tx(TransactionDefinition.PROPAGATION_REQUIRES_NEW).execute(status -> accounts
				.save(Account.openCustomer("owner-" + UUID.randomUUID(), Currency.EUR)).getId());
	}

	@Test
	void aStaleUpdateFailsInsteadOfOverwritingTheNewerBalance() {
		long id = newAccount();

		assertThatThrownBy(() -> tx(TransactionDefinition.PROPAGATION_REQUIRES_NEW).executeWithoutResult(outer -> {
			// Plain read, no row lock: this is the stale copy.
			Account stale = accounts.findById(id).orElseThrow();

			// A concurrent transaction credits the same account and commits first.
			tx(TransactionDefinition.PROPAGATION_REQUIRES_NEW).executeWithoutResult(inner -> {
				accounts.findByIdForUpdate(id).orElseThrow().credit(100);
			});

			stale.credit(5);
		})).isInstanceOf(ObjectOptimisticLockingFailureException.class);

		// The committed credit survives; the stale write did not replace it.
		long balance = tx(TransactionDefinition.PROPAGATION_REQUIRES_NEW)
				.execute(status -> accounts.findById(id).orElseThrow().getBalance());
		assertThat(balance).isEqualTo(100);
	}

	@Test
	void everyBalanceChangeBumpsTheVersion() {
		long id = newAccount();

		long before = tx(TransactionDefinition.PROPAGATION_REQUIRES_NEW)
				.execute(status -> accounts.findById(id).orElseThrow().getVersion());
		tx(TransactionDefinition.PROPAGATION_REQUIRES_NEW)
				.executeWithoutResult(status -> accounts.findByIdForUpdate(id).orElseThrow().credit(10));
		long after = tx(TransactionDefinition.PROPAGATION_REQUIRES_NEW)
				.execute(status -> accounts.findById(id).orElseThrow().getVersion());

		assertThat(after).isEqualTo(before + 1);
	}

}
