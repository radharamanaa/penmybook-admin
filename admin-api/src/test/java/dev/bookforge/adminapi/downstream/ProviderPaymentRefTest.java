package dev.bookforge.adminapi.downstream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderPaymentRefTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void prefersTheCurrentField() throws Exception {
        assertThat(HttpDownstreamClient.providerPaymentRef(mapper.readTree(
                "{\"providerPaymentRef\":\"pi_new\",\"paddleTransactionId\":\"txn_old\"}")))
                .isEqualTo("pi_new");
    }

    @Test
    void aBlankCurrentFieldFallsBackToTheLegacyId() throws Exception {
        assertThat(HttpDownstreamClient.providerPaymentRef(mapper.readTree(
                "{\"providerPaymentRef\":\"\",\"paddleTransactionId\":\"txn_old\"}")))
                .isEqualTo("txn_old");
    }

    @Test
    void aMissingIdStaysNull() throws Exception {
        assertThat(HttpDownstreamClient.providerPaymentRef(mapper.readTree("{}"))).isNull();
    }
}
