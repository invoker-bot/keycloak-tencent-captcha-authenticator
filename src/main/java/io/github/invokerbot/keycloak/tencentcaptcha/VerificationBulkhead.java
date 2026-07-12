package io.github.invokerbot.keycloak.tencentcaptcha;

import java.util.concurrent.Semaphore;

final class VerificationBulkhead {
    static final int MAX_CONCURRENT_VERIFICATIONS = 32;

    private static final VerificationBulkhead SHARED = new VerificationBulkhead(MAX_CONCURRENT_VERIFICATIONS);

    private final Semaphore permits;

    VerificationBulkhead(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("verification-bulkhead-capacity-invalid");
        }
        permits = new Semaphore(capacity);
    }

    static VerificationBulkhead shared() {
        return SHARED;
    }

    boolean tryAcquire() {
        return permits.tryAcquire();
    }

    void release() {
        permits.release();
    }
}
