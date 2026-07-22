package top.eiyooooo.easycontrol.app.adb;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;

public class AdbKeyPairTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @BeforeClass
    public static void installBase64Adapter() {
        AdbKeyPair.setAdbBase64(new AdbBase64() {
            @Override
            public String encodeToString(byte[] data) {
                return Base64.getEncoder().encodeToString(data);
            }

            @Override
            public byte[] decode(byte[] data) {
                return Base64.getDecoder().decode(data);
            }
        });
    }

    @Test
    public void generatedIdentityCanBeLoadedForAuthAndTls() throws Exception {
        File privateKey = temporaryFolder.newFile("private.key");
        File publicKey = temporaryFolder.newFile("public.key");
        AdbKeyPair.generate(privateKey, publicKey);

        AdbKeyPair identity = AdbKeyPair.read(privateKey, publicKey);

        assertNotNull(identity.getPrivateKey());
        assertNotNull(identity.getPublicKey());
        assertNotNull(identity.publicKeyBytes);
    }

    @Test
    public void rejectsMismatchedPublicAndPrivateFiles() throws Exception {
        File privateKey = temporaryFolder.newFile("private.key");
        File publicKey = temporaryFolder.newFile("public.key");
        AdbKeyPair.generate(privateKey, publicKey);
        String stored = new String(Files.readAllBytes(publicKey.toPath()), StandardCharsets.UTF_8);
        char replacement = stored.charAt(0) == 'A' ? 'B' : 'A';
        try (FileWriter writer = new FileWriter(publicKey)) {
            writer.write(replacement + stored.substring(1));
        }

        try {
            AdbKeyPair.read(privateKey, publicKey);
            fail("Expected IOException");
        } catch (IOException expected) {
            // Expected.
        }
    }

    @Test
    public void acceptsLineWrappedAndroidPublicKey() throws Exception {
        File privateKey = temporaryFolder.newFile("private.key");
        File publicKey = temporaryFolder.newFile("public.key");
        AdbKeyPair.generate(privateKey, publicKey);
        String stored = new String(Files.readAllBytes(publicKey.toPath()), StandardCharsets.UTF_8);
        int commentOffset = stored.indexOf(' ');
        String base64 = stored.substring(0, commentOffset);
        StringBuilder wrapped = new StringBuilder();
        for (int i = 0; i < base64.length(); i += 64) {
            if (wrapped.length() > 0) wrapped.append('\n');
            wrapped.append(base64, i, Math.min(i + 64, base64.length()));
        }
        wrapped.append(stored.substring(commentOffset));
        try (FileWriter writer = new FileWriter(publicKey)) {
            writer.write(wrapped.toString());
        }

        AdbKeyPair identity = AdbKeyPair.read(privateKey, publicKey);

        assertNotNull(identity.getPublicKey());
        assertTrue(identity.publicKeyBytes.length > 524);
    }
}
