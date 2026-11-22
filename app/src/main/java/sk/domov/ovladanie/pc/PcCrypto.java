package sk.domov.ovladanie.pc;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Kryptografia protokolu Domov PC (verzia 1), rovnaké kroky ako PC program (pc/crypto.go).
 * Len štandardné JDK triedy, takže sa dá testovať aj mimo Androidu.
 */
public final class PcCrypto {
    private PcCrypto() {}

    public static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    public static final int CODE_LEN = 10;
    public static final int PAIR_ITERATIONS = 200000;

    private static final SecureRandom RNG = new SecureRandom();

    public static byte[] random(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    public static byte[] hmac(byte[] key, byte[]... parts) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            for (byte[] p : parts) m.update(p);
            return m.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Párovací kód: veľké písmená bez pomlčiek a medzier; prázdny reťazec, ak je neplatný. */
    public static String normalizeCode(String code) {
        if (code == null) return "";
        StringBuilder sb = new StringBuilder();
        String up = code.toUpperCase(java.util.Locale.ROOT);
        for (int i = 0; i < up.length(); i++) {
            char c = up.charAt(i);
            if (c == '-' || c == ' ') continue;
            if (ALPHABET.indexOf(c) < 0) return "";
            sb.append(c);
        }
        return sb.length() == CODE_LEN ? sb.toString() : "";
    }

    /** Kľúč odvodený z párovacieho kódu (pomalé PBKDF2, ~0,2 s na telefóne). */
    public static byte[] pairKey(String code, byte[] serverId) {
        String norm = normalizeCode(code);
        if (norm.isEmpty()) throw new IllegalArgumentException("neplatný kód");
        try {
            byte[] prefix = "domov-pc/1/pair".getBytes(StandardCharsets.US_ASCII);
            byte[] salt = new byte[prefix.length + serverId.length];
            System.arraycopy(prefix, 0, salt, 0, prefix.length);
            System.arraycopy(serverId, 0, salt, prefix.length, serverId.length);
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(new PBEKeySpec(norm.toCharArray(), salt, PAIR_ITERATIONS, 256)).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Dôkaz v handshaku; role je "server" alebo "client". */
    public static byte[] proof(byte[] key, String role, int mode, byte[] deviceId, byte[] first, byte[] second,
                               byte[] serverId, byte[] name) {
        return hmac(key,
                ("domov-pc/1/" + role).getBytes(StandardCharsets.US_ASCII),
                new byte[]{(byte) mode}, deviceId, first, second, serverId,
                new byte[]{(byte) name.length}, name);
    }

    public static boolean equal(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    /** Dva kľúče relácie: [0] klient→server, [1] server→klient. */
    public static byte[][] sessionKeys(byte[] key, byte[] clientNonce, byte[] serverNonce) {
        byte[] salt = new byte[clientNonce.length + serverNonce.length];
        System.arraycopy(clientNonce, 0, salt, 0, clientNonce.length);
        System.arraycopy(serverNonce, 0, salt, clientNonce.length, serverNonce.length);
        byte[] prk = hmac(salt, key);
        byte[] c2s = hmac(prk, "domov-pc/1/c2s".getBytes(StandardCharsets.US_ASCII), new byte[]{1});
        byte[] s2c = hmac(prk, "domov-pc/1/s2c".getBytes(StandardCharsets.US_ASCII), new byte[]{1});
        return new byte[][]{c2s, s2c};
    }

    static byte[] nonce(int dir, long counter) {
        byte[] n = new byte[12];
        n[0] = (byte) dir;
        for (int i = 0; i < 8; i++) n[4 + i] = (byte) (counter >>> (56 - 8 * i));
        return n;
    }

    /** AES-256-GCM zapečatenie: výstup = šifrový text + 16 B značka (rovnako ako Go). */
    public static byte[] seal(byte[] key, int dir, long counter, byte[] plain) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce(dir, counter)));
        return c.doFinal(plain);
    }

    public static byte[] open(byte[] key, int dir, long counter, byte[] sealed) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce(dir, counter)));
        return c.doFinal(sealed);
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    public static byte[] unhex(String s) {
        if (s == null || s.length() % 2 != 0) throw new IllegalArgumentException("neplatný hex");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}
