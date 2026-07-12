package io.github.invokerbot.keycloak.tencentcaptcha;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

record CaptchaSecrets(String captchaAppId, String captchaAppSecretKey, String tencentSecretId,
        String tencentSecretKey) {
    private static final int MAX_FILE_BYTES = 65_536;
    private static final Set<String> ALLOWED_KEYS = Set.of("CAPTCHA_APP_ID", "CAPTCHA_APP_SECRET_KEY",
            "TENCENT_SECRET_ID", "TENCENT_SECRET_KEY");
    private static final Set<PosixFilePermission> MODE_0400 = Set.of(PosixFilePermission.OWNER_READ);
    private static final Set<PosixFilePermission> MODE_0600 = Set.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    static CaptchaSecrets load(Path path) throws IOException {
        if (path == null) {
            throw new IOException("secret-path-missing");
        }
        Path normalized = path.toAbsolutePath().normalize();
        validateParent(normalized);
        PosixFileAttributes before = validateSecretFile(normalized);
        String content = readBoundedUtf8(normalized);
        PosixFileAttributes after = validateSecretFile(normalized);
        if (before.fileKey() == null || after.fileKey() == null || !before.fileKey().equals(after.fileKey())) {
            throw new IOException("secret-file-identity-changed");
        }
        if (content.indexOf('\r') >= 0) {
            throw new IOException("secret-file-carriage-return");
        }
        if (content.endsWith("\n")) {
            content = content.substring(0, content.length() - 1);
        }
        String[] lines = content.split("\n", -1);
        if (lines.length != ALLOWED_KEYS.size()) {
            throw new IOException("secret-file-line-count");
        }

        Map<String, String> values = new HashMap<>();
        for (String line : lines) {
            int separator = line.indexOf('=');
            if (separator <= 0) {
                throw new IOException("secret-file-entry-invalid");
            }
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (!ALLOWED_KEYS.contains(key)) {
                throw new IOException("secret-file-key-unknown");
            }
            if (values.putIfAbsent(key, value) != null) {
                throw new IOException("secret-file-key-duplicate");
            }
            if (value.isBlank()) {
                throw new IOException("secret-file-value-blank");
            }
        }
        if (!values.keySet().equals(ALLOWED_KEYS)) {
            throw new IOException("secret-file-key-missing");
        }

        String appId = values.get("CAPTCHA_APP_ID");
        if (!appId.matches("[0-9]+")) {
            throw new IOException("captcha-app-id-not-decimal");
        }
        return new CaptchaSecrets(appId, values.get("CAPTCHA_APP_SECRET_KEY"), values.get("TENCENT_SECRET_ID"),
                values.get("TENCENT_SECRET_KEY"));
    }

    private static String readBoundedUtf8(Path path) throws IOException {
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        byte[] bytes;
        try (SeekableByteChannel channel = Files.newByteChannel(path, options)) {
            bytes = Channels.newInputStream(channel).readNBytes(MAX_FILE_BYTES + 1);
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IOException("secret-file-too-large");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("secret-file-invalid-utf8", exception);
        }
    }

    private static void validateParent(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent == null) {
            throw new IOException("secret-parent-missing");
        }
        PosixFileAttributes attributes = readAttributes(parent, "secret-parent-attributes-unavailable");
        if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
            throw new IOException("secret-parent-not-trusted-directory");
        }
        Set<PosixFilePermission> permissions = attributes.permissions();
        if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
            throw new IOException("secret-parent-writable-by-non-owner");
        }
    }

    private static PosixFileAttributes validateSecretFile(Path path) throws IOException {
        PosixFileAttributes attributes = readAttributes(path, "secret-file-attributes-unavailable");
        if (attributes.isSymbolicLink()) {
            throw new IOException("secret-file-symlink");
        }
        if (!attributes.isRegularFile()) {
            throw new IOException("secret-file-not-regular");
        }
        Set<PosixFilePermission> mode = attributes.permissions();
        if (!mode.equals(MODE_0400) && !mode.equals(MODE_0600)) {
            throw new IOException("secret-file-mode-invalid");
        }
        return attributes;
    }

    private static PosixFileAttributes readAttributes(Path path, String error) throws IOException {
        try {
            return Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException exception) {
            throw new IOException(error, exception);
        }
    }
}
