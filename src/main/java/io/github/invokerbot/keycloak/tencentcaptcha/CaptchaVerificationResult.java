package io.github.invokerbot.keycloak.tencentcaptcha;

record CaptchaVerificationResult(boolean accepted, String category, Integer code, String apiErrorCode,
        String requestId) {
    CaptchaVerificationResult(boolean accepted, String category, Integer code) {
        this(accepted, category, code, null, null);
    }

    CaptchaVerificationResult {
        apiErrorCode = apiErrorCode != null && apiErrorCode.matches("[A-Z][A-Za-z0-9.]{0,99}") ? apiErrorCode : null;
        requestId = requestId != null
                && requestId.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
                        ? requestId
                        : null;
    }

    boolean operationalFailure() {
        return !accepted && switch (category) {
            case "api-error", "transport-error", "invalid-response", "captcha-config-unavailable", "busy" -> true;
            default -> false;
        };
    }
}
