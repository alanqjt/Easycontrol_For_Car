package top.eiyooooo.easycontrol.app.adb;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;

import javax.crypto.Cipher;
/**
 * 类 AdbKeyPair
 * 说明：该类负责 AdbKeyPair 相关功能。
 */

public class AdbKeyPair {

  private final PrivateKey privateKey;
  private final RSAPublicKey publicKey;
  public final byte[] publicKeyBytes;

  private AdbKeyPair(PrivateKey privateKey, RSAPublicKey publicKey, byte[] publicKeyBytes) {
    this.privateKey = privateKey;
    this.publicKey = publicKey;
    this.publicKeyBytes = publicKeyBytes;
  }

  public PrivateKey getPrivateKey() {
    return privateKey;
  }

  public RSAPublicKey getPublicKey() {
    return publicKey;
  }

  public byte[] signPayload(ByteBuffer payload) throws Exception {
    if (payload == null) return new byte[]{0};
    byte[] token = new byte[payload.remaining()];
    payload.duplicate().get(token);
    Cipher cipher = Cipher.getInstance("RSA/ECB/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, privateKey);
    cipher.update(SIGNATURE_PADDING);
    return cipher.doFinal(token);
  }

  public static void setAdbBase64(AdbBase64 adbBase64) {
    AdbKeyPair.adbBase64 = adbBase64;
  }

  public static AdbKeyPair read(File privateKey, File publicKey) throws Exception {
    if (adbBase64 == null) throw new IOException("no adbBase64");
    byte[] storedPublicKeyBytes = new byte[(int) publicKey.length()];
    byte[] privateKeyBytes = new byte[(int) privateKey.length()];
    PrivateKey tmpPrivateKey;

    try (FileInputStream stream = new FileInputStream(publicKey)) {
      readFully(stream, storedPublicKeyBytes);
    }
    try (FileInputStream stream = new FileInputStream(privateKey)) {
      readFully(stream, privateKeyBytes);
      String data = new String(privateKeyBytes, StandardCharsets.UTF_8)
              .replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replace("\n", "")
              .replace("\r", "");
      privateKeyBytes = adbBase64.decode(data.getBytes(StandardCharsets.UTF_8));
    }

    KeyFactory keyFactory = KeyFactory.getInstance("RSA");
    tmpPrivateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
    String storedPublicKey = new String(storedPublicKeyBytes, StandardCharsets.UTF_8).trim();
    ParsedPublicKey parsedPublicKey = parseAdbPublicKey(storedPublicKey, keyFactory);
    RSAPublicKey rsaPublicKey = parsedPublicKey.publicKey;
    verifyKeyPair(tmpPrivateKey, rsaPublicKey);
    String expectedBase64 = adbBase64.encodeToString(parsedPublicKey.adbEncodedKey)
            .replace("\n", "").replace("\r", "");
    byte[] encodedPublicKey = (expectedBase64 + " " + parsedPublicKey.keyName)
            .getBytes(StandardCharsets.UTF_8);
    byte[] publicKeyBytes = new byte[encodedPublicKey.length + 1];
    System.arraycopy(encodedPublicKey, 0, publicKeyBytes, 0, encodedPublicKey.length);

    return new AdbKeyPair(tmpPrivateKey, rsaPublicKey, publicKeyBytes);
  }

  private static void readFully(FileInputStream stream, byte[] output) throws IOException {
    int offset = 0;
    while (offset < output.length) {
      int read = stream.read(output, offset, output.length - offset);
      if (read < 0) throw new IOException("Unexpected end of ADB key file");
      offset += read;
    }
  }

  private static ParsedPublicKey parseAdbPublicKey(String storedPublicKey, KeyFactory keyFactory)
          throws Exception {
    String[] parts = storedPublicKey.split("\\s+");
    StringBuilder encoded = new StringBuilder();
    byte[] adbEncodedKey = null;
    int nameIndex = parts.length;
    for (int i = 0; i < parts.length; i++) {
      if (!parts[i].matches("[A-Za-z0-9+/=]+")) break;
      encoded.append(parts[i]);
      try {
        byte[] decoded = adbBase64.decode(encoded.toString().getBytes(StandardCharsets.UTF_8));
        if (decoded.length == ADB_PUBLIC_KEY_LENGTH) {
          adbEncodedKey = decoded;
          nameIndex = i + 1;
          break;
        }
        if (decoded.length > ADB_PUBLIC_KEY_LENGTH) break;
      } catch (IllegalArgumentException ignored) {
        // A wrapped Base64 token may be incomplete; keep collecting tokens.
      }
    }
    if (adbEncodedKey == null) throw new IOException("Invalid Android ADB public key");

    ByteBuffer adbKey = ByteBuffer.wrap(adbEncodedKey).order(ByteOrder.LITTLE_ENDIAN);
    int wordCount = adbKey.getInt();
    if (wordCount != KEY_LENGTH_WORDS) {
      throw new IOException("Unsupported Android ADB public key word count: " + wordCount);
    }
    adbKey.getInt(); // n0inv
    byte[] modulusLittleEndian = new byte[KEY_LENGTH_BYTES];
    adbKey.get(modulusLittleEndian);
    byte[] modulusBigEndian = new byte[KEY_LENGTH_BYTES];
    for (int i = 0; i < KEY_LENGTH_BYTES; i++) {
      modulusBigEndian[i] = modulusLittleEndian[KEY_LENGTH_BYTES - 1 - i];
    }
    adbKey.position(ADB_PUBLIC_KEY_LENGTH - 4);
    long exponent = adbKey.getInt() & 0xffffffffL;
    if (exponent <= 1) throw new IOException("Invalid Android ADB public exponent");
    RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(
            new RSAPublicKeySpec(new BigInteger(1, modulusBigEndian), BigInteger.valueOf(exponent)));

    StringBuilder keyName = new StringBuilder();
    for (int i = nameIndex; i < parts.length; i++) {
      if (keyName.length() > 0) keyName.append(' ');
      keyName.append(parts[i]);
    }
    return new ParsedPublicKey(publicKey, adbEncodedKey,
            keyName.length() == 0 ? "one@Aphone" : keyName.toString());
  }

  private static void verifyKeyPair(PrivateKey privateKey, RSAPublicKey publicKey) throws Exception {
    byte[] challenge = "Easycontrol ADB identity".getBytes(StandardCharsets.UTF_8);
    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(privateKey);
    signer.update(challenge);
    byte[] signature = signer.sign();
    signer.initVerify(publicKey);
    signer.update(challenge);
    if (!signer.verify(signature)) throw new IOException("ADB public key does not match private key");
  }

  private static final class ParsedPublicKey {
    final RSAPublicKey publicKey;
    final byte[] adbEncodedKey;
    final String keyName;

    ParsedPublicKey(RSAPublicKey publicKey, byte[] adbEncodedKey, String keyName) {
      this.publicKey = publicKey;
      this.adbEncodedKey = adbEncodedKey;
      this.keyName = keyName;
    }
  }

  public static void generate(File privateKey, File publicKey) throws Exception {
    if (adbBase64 == null) throw new IOException("no adbBase64");
    KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
    keyPairGenerator.initialize(KEY_LENGTH_BITS);
    KeyPair keyPair = keyPairGenerator.genKeyPair();

    try (FileWriter publicKeyWriter = new FileWriter(publicKey)) {
      publicKeyWriter.write(adbBase64.encodeToString(convertRsaPublicKeyToAdbFormat((RSAPublicKey) keyPair.getPublic())).replace("\n", ""));
      publicKeyWriter.write(" one@Aphone");
      publicKeyWriter.flush();
    }
    try (FileWriter privateKeyWriter = new FileWriter(privateKey)) {
      privateKeyWriter.write("-----BEGIN PRIVATE KEY-----\n");
      privateKeyWriter.write(adbBase64.encodeToString(keyPair.getPrivate().getEncoded()).replace("\n", ""));
      privateKeyWriter.write("\n-----END PRIVATE KEY-----");
      privateKeyWriter.flush();
    }
  }

  private static byte[] convertRsaPublicKeyToAdbFormat(RSAPublicKey pubkey) {
    BigInteger r32, r, rr, rem, n, n0inv;

    r32 = BigInteger.ZERO.setBit(32);
    n = pubkey.getModulus();
    r = BigInteger.ZERO.setBit(KEY_LENGTH_WORDS * 32);
    rr = r.modPow(BigInteger.valueOf(2), n);
    rem = n.remainder(r32);
    n0inv = rem.modInverse(r32);

    int[] myN = new int[KEY_LENGTH_WORDS];
    int[] myRr = new int[KEY_LENGTH_WORDS];
    BigInteger[] res;
    for (int i = 0; i < KEY_LENGTH_WORDS; i++) {
      res = rr.divideAndRemainder(r32);
      rr = res[0];
      rem = res[1];
      myRr[i] = rem.intValue();

      res = n.divideAndRemainder(r32);
      n = res[0];
      rem = res[1];
      myN[i] = rem.intValue();
    }

    ByteBuffer bbuf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
    bbuf.putInt(KEY_LENGTH_WORDS);
    bbuf.putInt(n0inv.negate().intValue());
    for (int i : myN) bbuf.putInt(i);
    for (int i : myRr) bbuf.putInt(i);

    bbuf.putInt(pubkey.getPublicExponent().intValue());
    return bbuf.array();
  }


  private static final int KEY_LENGTH_BITS = 2048;
  private static final int KEY_LENGTH_BYTES = KEY_LENGTH_BITS / 8;
  private static final int KEY_LENGTH_WORDS = KEY_LENGTH_BYTES / 4;
  private static final int ADB_PUBLIC_KEY_LENGTH = 524;
  private static AdbBase64 adbBase64;

  public static final byte[] SIGNATURE_PADDING = new byte[]{
    (byte) 0x00, (byte) 0x01, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
    (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
    (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
    (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
    (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
    (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
    (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
    (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
    (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
    (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
    (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
    (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
    (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
    (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
    (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
    (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
    (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0x00,
    (byte) 0x30, (byte) 0x21, (byte) 0x30, (byte) 0x09, (byte) 0x06, (byte) 0x05, (byte) 0x2b, (byte) 0x0e, (byte) 0x03, (byte) 0x02, (byte) 0x1a, (byte) 0x05, (byte) 0x00,
    (byte) 0x04, (byte) 0x14
  };

}
