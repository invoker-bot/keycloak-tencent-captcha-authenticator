package io.github.invokerbot.keycloak.tencentcaptcha;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VerificationBulkheadTest {
    @Test
    void acquisitionIsBoundedNonBlockingAndReleasedPermitsAreReusable() {
        VerificationBulkhead bulkhead = new VerificationBulkhead(2);

        assertTrue(bulkhead.tryAcquire());
        assertTrue(bulkhead.tryAcquire());
        assertFalse(bulkhead.tryAcquire());

        bulkhead.release();
        assertTrue(bulkhead.tryAcquire());
    }
}
