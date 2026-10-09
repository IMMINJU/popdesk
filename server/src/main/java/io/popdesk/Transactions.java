package io.popdesk;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a unit of work in a new transaction and retries it once, in another new transaction, when it
 * fails on a lock timeout ({@code 55P03}) or a deadlock ({@code 40P01}) (conventions "Retrying on lock
 * conflicts", S0-AC-85). A second failure goes to the caller and becomes a 5xx. Services call this
 * around a write instead of {@code @Transactional} when the write takes row locks another request can
 * hold. Never call it inside a transaction: the retry must start from the beginning.
 */
@Component
public class Transactions {

    private static final Logger log = LoggerFactory.getLogger(Transactions.class);

    private final TransactionTemplate template;

    Transactions(PlatformTransactionManager transactionManager) {
        this.template = new TransactionTemplate(transactionManager);
        this.template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <T> T run(Supplier<T> work) {
        try {
            return template.execute(status -> work.get());
        } catch (RuntimeException e) {
            if (!SqlStates.retryable(e)) {
                throw e;
            }
            log.warn("A transaction failed on a lock (SQL state {}); retrying it once.", SqlStates.of(e).orElse("?"));
            return template.execute(status -> work.get());
        }
    }

    public void run(Runnable work) {
        run(() -> {
            work.run();
            return null;
        });
    }
}
