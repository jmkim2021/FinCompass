package com.loanops.personal.openbanking;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import static com.loanops.personal.openbanking.OpenBankingException.Category.CONFIGURATION;
import static com.loanops.personal.openbanking.OpenBankingException.Category.STORAGE_ERROR;

/** AES-256-GCM, separate authenticated context per record; no default or generated persisted key. */
public final class SecretBox {
    private final byte[] key;
    private final String keyId;
    private final SecureRandom random = new SecureRandom();
    public SecretBox(String encodedKey, String keyId) {
        try { key = Base64.getDecoder().decode(encodedKey); }
        catch (IllegalArgumentException ex) { throw new OpenBankingException(CONFIGURATION); }
        if (key.length != 32 || !keyId.matches("[a-zA-Z0-9_-]{1,40}")) throw new OpenBankingException(CONFIGURATION);
        this.keyId = keyId;
    }
    public String seal(String context, String plaintext) {
        try {
            byte[] iv = new byte[12]; random.nextBytes(iv);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, context, iv);
            return keyId + "." + Base64.getEncoder().encodeToString(iv) + "."
                    + Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) { throw new OpenBankingException(STORAGE_ERROR); }
    }
    public String open(String context, String envelope) {
        try {
            String[] parts = envelope.split("\\.", -1);
            if (parts.length != 3 || !keyId.equals(parts[0])) throw new IllegalArgumentException();
            return new String(cipher(Cipher.DECRYPT_MODE, context, Base64.getDecoder().decode(parts[1]))
                    .doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) { throw new OpenBankingException(STORAGE_ERROR); }
    }
    private Cipher cipher(int mode, String context, byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD((keyId + ":" + context).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
    public String identity(String canonicalValue) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(("ob-identity-v1:" + canonicalValue).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) { throw new OpenBankingException(STORAGE_ERROR); }
    }
    @Override public String toString() { return "SecretBox[REDACTED]"; }
}
