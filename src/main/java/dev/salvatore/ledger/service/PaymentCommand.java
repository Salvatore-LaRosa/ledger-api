package dev.salvatore.ledger.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** What the client asks for in a payment. Validated on construction. */
public record PaymentCommand(UUID sourceAccountId, UUID destinationAccountId, long amount) {

    public PaymentCommand {
        Objects.requireNonNull(sourceAccountId, "sourceAccountId must not be null");
        Objects.requireNonNull(destinationAccountId, "destinationAccountId must not be null");
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive, got " + amount);
        }
        if (sourceAccountId.equals(destinationAccountId)) {
            throw new IllegalArgumentException("source and destination accounts must be different");
        }
    }

    /**
     * SHA-256 (hex) of a canonical rendering of the request. Hashing the fields and not the raw
     * JSON body means formatting differences (spaces, field order) do not change the hash. The
     * "v1" prefix lets the format evolve.
     */
    String requestHash() {
        String canonical = "v1|%s|%s|%d".formatted(sourceAccountId, destinationAccountId, amount);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}