package com.paytm.wallet;

import com.paytm.wallet.api.BearerTokens;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class BearerTokensTest {
    private final BearerTokens tokens = new BearerTokens("unit-test-secret-not-for-production-123456");

    @Test
    void validTokenIdentifiesOnlyItsSignedUser() {
        String token = tokens.issue("alice", Instant.now().plusSeconds(60));
        assertThat(tokens.verify(token)).isEqualTo("alice");
        assertThatThrownBy(() -> tokens.verify("x" + token)).isInstanceOf(IllegalArgumentException.class);
        BearerTokens otherKey = new BearerTokens("different-test-secret-not-for-production-123456");
        assertThatThrownBy(() -> otherKey.verify(token)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidExpiredAndMalformedTokensAreRejected() {
        for (String token : new String[]{"alice", "x.y.z", ".", "not-base64.!", "x".repeat(513),
                tokens.issue("alice", Instant.now().minusSeconds(1))}) {
            assertThatThrownBy(() -> tokens.verify(token)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> tokens.issue("alice:admin", Instant.now())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BearerTokens("short")).isInstanceOf(IllegalArgumentException.class);
    }
}