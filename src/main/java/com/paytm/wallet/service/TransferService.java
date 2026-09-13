package com.paytm.wallet.service;

import com.paytm.wallet.api.ApiException;
import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.repository.TransferRepository;
import com.paytm.wallet.repository.WalletRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";

    private final WalletRepository walletRepository;
    private final TransferRepository transferRepository;
    private final Counter createdCounter;
    private final Counter declinedCounter;
    private final Counter replayCounter;
    private final MeterRegistry meterRegistry;

    public TransferService(
            WalletRepository walletRepository,
            TransferRepository transferRepository,
            MeterRegistry meterRegistry) {
        this.walletRepository = walletRepository;
        this.transferRepository = transferRepository;
        this.meterRegistry = meterRegistry;
        this.createdCounter = meterRegistry.counter("wallet.transfers.recorded");
        this.declinedCounter = meterRegistry.counter("wallet.transfers.declined", "reason", "insufficient_funds");
        this.replayCounter = meterRegistry.counter("wallet.transfers.idempotent.replays");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 20)
    public Transfer create(
            String userId,
            UUID from,
            UUID to,
            long amountPaise,
            String idempotencyKey) {
        if (from == null || to == null || amountPaise <= 0 || idempotencyKey == null
                || idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", "Invalid transfer parameters");
        }
        if (from.equals(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "same_wallet", "Source and destination must differ");
        }

        // Ownership/existence checks do not lock rows. Wallet identities are immutable;
        // foreign keys remain the database backstop. Never claim a key for an unauthorized caller.
        List<Wallet> identities = walletRepository.findTransferWallets(from, to);
        Wallet ownedSource = identities.stream().filter(wallet -> wallet.id().equals(from)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "wallet_not_found", "Source wallet not found"));
        if (!ownedSource.userId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "wallet_forbidden", "Source wallet belongs to another user");
        }
        if (identities.size() != 2) {
            throw new ApiException(HttpStatus.NOT_FOUND, "wallet_not_found", "Destination wallet not found");
        }

        UUID transferId = UUID.randomUUID();
        boolean inserted = transferRepository.tryInsert(transferId, idempotencyKey, from, to, amountPaise);
        if (!inserted) {
            return replayExisting(userId, from, to, amountPaise, idempotencyKey);
        }

        List<Wallet> lockedWallets = walletRepository.lockForTransfer(from, to);
        if (lockedWallets.size() != 2) {
            throw new ApiException(HttpStatus.NOT_FOUND, "wallet_not_found", "Source or destination wallet not found");
        }

        Wallet source = lockedWallets.stream()
                .filter(wallet -> wallet.id().equals(from))
                .findFirst()
                .orElseThrow();
        if (!source.userId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "wallet_forbidden", "Source wallet belongs to another user");
        }

        Wallet destination = lockedWallets.stream().filter(wallet -> wallet.id().equals(to)).findFirst().orElseThrow();
        if (source.balancePaise() >= amountPaise && destination.balancePaise() > Long.MAX_VALUE - amountPaise) {
            Transfer finalized = transferRepository.markDeclined(transferId, "BALANCE_LIMIT_EXCEEDED");
            afterCommit(() -> {
                createdCounter.increment();
                meterRegistry.counter("wallet.transfers.declined", "reason", "balance_limit_exceeded").increment();
                log.atInfo().addKeyValue("event", "transfer.declined")
                        .addKeyValue("transfer_id", transferId).addKeyValue("reason", "BALANCE_LIMIT_EXCEEDED")
                        .log("Transfer declined");
            });
            return finalized;
        }

        if (!walletRepository.debitIfSufficient(from, amountPaise)) {
            Transfer finalized = transferRepository.markDeclined(transferId, INSUFFICIENT_FUNDS);
            afterCommit(() -> {
                createdCounter.increment();
                declinedCounter.increment();
                log.atInfo()
                        .addKeyValue("event", "transfer.declined")
                        .addKeyValue("transfer_id", transferId)
                        .addKeyValue("from_wallet_id", from)
                        .addKeyValue("amount_paise", amountPaise)
                        .addKeyValue("reason", INSUFFICIENT_FUNDS)
                        .log("Transfer declined");
            });
            return finalized;
        }

        walletRepository.credit(to, amountPaise);
        Transfer finalized = transferRepository.markSucceeded(transferId);
        afterCommit(() -> {
            createdCounter.increment();
            log.atInfo()
                    .addKeyValue("event", "transfer.created")
                    .addKeyValue("transfer_id", transferId)
                    .addKeyValue("idempotency_key", idempotencyKey)
                    .log("Transfer committed");
            log.atInfo()
                    .addKeyValue("event", "wallet.debited")
                    .addKeyValue("transfer_id", transferId)
                    .addKeyValue("wallet_id", from)
                    .addKeyValue("amount_paise", amountPaise)
                    .log("Wallet debited");
            log.atInfo()
                    .addKeyValue("event", "wallet.credited")
                    .addKeyValue("transfer_id", transferId)
                    .addKeyValue("wallet_id", to)
                    .addKeyValue("amount_paise", amountPaise)
                    .log("Wallet credited");
        });
        return finalized;
    }

    public Transfer get(UUID id, String userId) {
        return transferRepository.findByIdForUser(id, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "transfer_not_found", "Transfer not found"));
    }

    private Transfer replayExisting(
            String userId,
            UUID from,
            UUID to,
            long amountPaise,
            String idempotencyKey) {
        Transfer existing = transferRepository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Idempotency conflict completed without a transfer"));
        if (!existing.hasSameIntent(from, to, amountPaise)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "idempotency_conflict",
                    "Idempotency key was already used for a different transfer");
        }
        Wallet source = walletRepository.findById(existing.fromWalletId())
                .orElseThrow(() -> new IllegalStateException("Transfer source wallet disappeared"));
        if (!source.userId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "wallet_forbidden", "Source wallet belongs to another user");
        }
        afterCommit(() -> {
            replayCounter.increment();
            log.atInfo()
                    .addKeyValue("event", "transfer.idempotent_replay")
                    .addKeyValue("transfer_id", existing.id())
                    .log("Idempotent replay returned existing transfer");
        });
        return existing;
    }

    private void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}