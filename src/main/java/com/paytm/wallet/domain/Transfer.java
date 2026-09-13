package com.paytm.wallet.domain;

import java.time.Instant;
import java.util.UUID;

public record Transfer(
        UUID id,
        String idempotencyKey,
        UUID fromWalletId,
        UUID toWalletId,
        long amountPaise,
        TransferStatus status,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    public boolean hasSameIntent(UUID from, UUID to, long amount) {
        return fromWalletId.equals(from) && toWalletId.equals(to) && amountPaise == amount;
    }
}