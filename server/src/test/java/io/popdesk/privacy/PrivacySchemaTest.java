package io.popdesk.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.BareAppTest;
import io.popdesk.support.DbInspector.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The column of migration V22 (S10A-AC-33, {@code design.md} 1절), read from the database: the name is the one the
 * design gives, so this test reads the schema itself (the exception the slice allows for a migration). No account is
 * anonymized in this context, so every account here is one V22 found or one made after it without an anonymization.
 */
class PrivacySchemaTest extends BareAppTest {

    @Test
    @DisplayName("S10A-AC-33 V22 adds account.anonymized_at, timestamptz, nullable, without a default, and leaves it "
            + "null on every account that was not anonymized")
    void accountHasAnonymizedAt() {
        fixtures.signInAdmin();

        Column column = db.columns("account").stream().filter(c -> c.name().equals("anonymized_at")).findFirst()
                .orElse(null);

        assertThat(column).as("account.anonymized_at").isNotNull();
        assertThat(column.dataType()).as("type of account.anonymized_at").isEqualTo("timestamp with time zone");
        assertThat(column.nullable()).as("account.anonymized_at is nullable").isTrue();
        assertThat(column.defaultValue()).as("default of account.anonymized_at").isNull();
        assertThat(db.count("select count(*) from account")).as("accounts").isPositive();
        assertThat(db.count("select count(*) from account where anonymized_at is not null"))
                .as("accounts with anonymized_at in a context that anonymizes nobody").isZero();
    }
}
