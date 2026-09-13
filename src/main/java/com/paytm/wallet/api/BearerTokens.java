package com.paytm.wallet.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;

/** Small exercise-specific token format, NOT JWT: base64url(user:expiry).base64url(HMAC). */
@Component
public class BearerTokens {
    private static final Pattern USER = Pattern.compile("[A-Za-z0-9._@-]{1,128}");
    private final byte[] secret;

    public BearerTokens(@Value("${wallet.auth-secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        if (this.secret.length < 32) {
            throw new IllegalArgumentException("WALLET_AUTH_SECRET must contain at least 32 bytes");
        }
    }

    public String verify(String token) {
        try {
            if (token.length() > 512) {
                throw new IllegalArgumentException();
            }
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(sign(parts[0]), Base64.getUrlDecoder().decode(parts[1]))) {
                throw new IllegalArgumentException();
            }
            String[] payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8).split(":", -1);
            if (payload.length != 2 || !USER.matcher(payload[0]).matches()
                    || Long.parseLong(payload[1]) <= Instant.now().getEpochSecond()) {
                throw new IllegalArgumentException();
            }
            return payload[0];
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid or expired bearer token");
        }
    }

    // No public HTTP issuance endpoint: only trusted operators possessing the secret may issue tokens.
    public String issue(String userId, Instant expiresAt) {
        if (!USER.matcher(userId).matches()) {
            throw new IllegalArgumentException("Invalid user ID");
        }
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                (userId + ":" + expiresAt.getEpochSecond()).getBytes(StandardCharsets.UTF_8));
        return payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC unavailable", exception);
        }
    }
}