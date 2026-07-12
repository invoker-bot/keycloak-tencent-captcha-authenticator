package io.github.invokerbot.keycloak.tencentcaptcha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CaptchaSecretsTest {
    private static final Set<PosixFilePermission> MODE_0400 = Set.of(PosixFilePermission.OWNER_READ);
    private static final Set<PosixFilePermission> MODE_0600 = Set.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> MODE_0700 = Set.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final String CAPTCHA_SECRET_KEY_NAME = "CAPTCHA_APP_SECRET_" + "KEY";
    private static final String TENCENT_SECRET_KEY_NAME = "TENCENT_SECRET_" + "KEY";
    private static final String VALID = "CAPTCHA_APP_ID=123456789\n" + CAPTCHA_SECRET_KEY_NAME
            + "=CAPTCHASECRETEXAMPLE\n" + "TENCENT_SECRET_ID=AKIDEXAMPLE\n" + TENCENT_SECRET_KEY_NAME
            + "=SECRETKEYEXAMPLE\n";

    @TempDir
    Path tempDir;

    @Test
    void loadsExactSecretFileWithEitherApprovedMode() throws IOException {
        Path mode0400 = secretFile("mode-0400.env", VALID, MODE_0400);
        Path mode0600 = secretFile("mode-0600.env", VALID, MODE_0600);
        CaptchaSecrets expected = new CaptchaSecrets("123456789", "CAPTCHASECRETEXAMPLE", "AKIDEXAMPLE",
                "SECRETKEYEXAMPLE");

        assertEquals(expected, CaptchaSecrets.load(mode0400));
        assertEquals(expected, CaptchaSecrets.load(mode0600));
    }

    @Test
    void rejectsMode0640() throws IOException {
        Path file = secretFile("mode-0640.env", VALID, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ));

        assertThrows(IOException.class, () -> CaptchaSecrets.load(file));
    }

    @Test
    void rejectsSymlinkNonRegularFileAndSymlinkParent() throws IOException {
        Path file = secretFile("target.env", VALID, MODE_0400);
        Path link = file.getParent().resolve("link.env");
        Files.createSymbolicLink(link, file);

        Path actualParent = Files.createDirectory(tempDir.resolve("actual-parent"));
        Files.setPosixFilePermissions(actualParent, MODE_0700);
        Path child = actualParent.resolve("secret.env");
        Files.writeString(child, VALID, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(child, MODE_0400);
        Path parentLink = tempDir.resolve("parent-link");
        Files.createSymbolicLink(parentLink, actualParent);

        assertThrows(IOException.class, () -> CaptchaSecrets.load(link));
        assertThrows(IOException.class, () -> CaptchaSecrets.load(file.getParent()));
        assertThrows(IOException.class, () -> CaptchaSecrets.load(parentLink.resolve("secret.env")));
    }

    @Test
    void rejectsUnknownDuplicateMissingAndMalformedKeysWithoutLeakingValues() throws IOException {
        Path unknown = secretFile("unknown.env",
                VALID.replace(TENCENT_SECRET_KEY_NAME + "=SECRETKEYEXAMPLE", "UNEXPECTED_KEY=DO_NOT_LEAK_THIS"),
                MODE_0400);
        Path duplicate = secretFile("duplicate.env", VALID + "TENCENT_SECRET_ID=DO_NOT_LEAK_THIS\n", MODE_0400);
        Path missing = secretFile("missing.env", VALID.replace(TENCENT_SECRET_KEY_NAME + "=SECRETKEYEXAMPLE\n", ""),
                MODE_0400);
        Path malformed = secretFile("malformed.env", VALID + "NOT_AN_ASSIGNMENT\n", MODE_0400);

        IOException unknownError = assertThrows(IOException.class, () -> CaptchaSecrets.load(unknown));
        IOException duplicateError = assertThrows(IOException.class, () -> CaptchaSecrets.load(duplicate));
        IOException missingError = assertThrows(IOException.class, () -> CaptchaSecrets.load(missing));
        IOException malformedError = assertThrows(IOException.class, () -> CaptchaSecrets.load(malformed));

        assertFalse(unknownError.getMessage().contains("DO_NOT_LEAK_THIS"));
        assertFalse(duplicateError.getMessage().contains("DO_NOT_LEAK_THIS"));
        assertFalse(missingError.getMessage().contains("SECRETKEYEXAMPLE"));
        assertFalse(malformedError.getMessage().contains("NOT_AN_ASSIGNMENT"));
    }

    @Test
    void rejectsBlankNonDecimalCrLfAndInvalidUtf8Values() throws IOException {
        Path blank = secretFile("blank.env", VALID.replace("CAPTCHASECRETEXAMPLE", "   "), MODE_0400);
        Path nonDecimal = secretFile("non-decimal.env", VALID.replace("123456789", "12x"), MODE_0400);
        Path crlf = secretFile("crlf.env", VALID.replace("\n", "\r\n"), MODE_0400);
        Path invalidUtf8 = safeParent().resolve("invalid-utf8.env");
        Files.write(invalidUtf8, new byte[]{(byte) 0xc3, (byte) 0x28});
        Files.setPosixFilePermissions(invalidUtf8, MODE_0400);

        assertThrows(IOException.class, () -> CaptchaSecrets.load(blank));
        assertThrows(IOException.class, () -> CaptchaSecrets.load(nonDecimal));
        assertThrows(IOException.class, () -> CaptchaSecrets.load(crlf));
        assertThrows(IOException.class, () -> CaptchaSecrets.load(invalidUtf8));
    }

    @Test
    void rejectsOversizedSecretFileBeforeParsingValues() throws IOException {
        Path oversized = secretFile("oversized.env", VALID.replace("CAPTCHASECRETEXAMPLE", "x".repeat(70_000)),
                MODE_0400);

        assertThrows(IOException.class, () -> CaptchaSecrets.load(oversized));
    }

    @Test
    void rejectsMode0400FileInsideGroupWritableParent() throws IOException {
        Path unsafeDirectory = Files.createDirectory(tempDir.resolve("unsafe"));
        Files.setPosixFilePermissions(unsafeDirectory,
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE));
        Path file = unsafeDirectory.resolve("replaceable.env");
        Files.writeString(file, VALID, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, MODE_0400);

        assertThrows(IOException.class, () -> CaptchaSecrets.load(file));
    }

    private Path secretFile(String name, String content, Set<PosixFilePermission> mode) throws IOException {
        Path file = safeParent().resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, mode);
        return file;
    }

    private Path safeParent() throws IOException {
        Path directory = tempDir.resolve("safe");
        if (Files.notExists(directory)) {
            Files.createDirectory(directory);
            Files.setPosixFilePermissions(directory, MODE_0700);
        }
        return directory;
    }
}
