package io.github.invokerbot.keycloak.tencentcaptcha;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Properties;

record SentryConfiguration(URI endpoint, String publicKey, String environment, String release) {
    static SentryConfiguration load() throws IOException, URISyntaxException {
        Properties defaults = new Properties();
        try (InputStream input = SentryConfiguration.class
                .getResourceAsStream("/META-INF/tencent-captcha-sentry.properties")) {
            if (input != null)
                defaults.load(input);
        }
        return resolve(System.getenv(), defaults);
    }

    static SentryConfiguration resolve(Map<String, String> environment, Properties defaults) throws URISyntaxException {
        String dsn = value("SENTRY_DSN", environment, defaults);
        if (dsn.isEmpty())
            return null;
        URI uri = new URI(dsn);
        String key = uri.getUserInfo();
        String path = uri.getPath();
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || key == null || !key.matches("[a-fA-F0-9]{32}")
                || uri.getQuery() != null || uri.getFragment() != null || path == null
                || !path.matches("(?:/[A-Za-z0-9_-]+)*/[0-9]+")) {
            throw new IllegalArgumentException("sentry-configuration-invalid");
        }
        int slash = path.lastIndexOf('/');
        URI endpoint = new URI("https", null, uri.getHost(), uri.getPort(),
                path.substring(0, slash) + "/api/" + path.substring(slash + 1) + "/envelope/", null, null);
        return new SentryConfiguration(endpoint, key, value("SENTRY_ENVIRONMENT", environment, defaults),
                value("SENTRY_RELEASE", environment, defaults));
    }

    private static String value(String key, Map<String, String> environment, Properties defaults) {
        String value = environment.containsKey(key) ? environment.get(key) : defaults.getProperty(key, "");
        return value == null ? "" : value.strip();
    }

    @Override
    public String toString() {
        return "SentryConfiguration[configured]";
    }
}
