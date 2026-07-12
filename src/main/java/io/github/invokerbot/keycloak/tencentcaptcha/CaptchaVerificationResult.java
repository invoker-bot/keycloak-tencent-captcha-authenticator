package io.github.invokerbot.keycloak.tencentcaptcha;

record CaptchaVerificationResult(boolean accepted, String category, Integer code) {
}
