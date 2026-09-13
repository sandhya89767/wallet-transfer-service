package com.paytm.wallet.service;

import com.paytm.wallet.api.ApiException;
import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.repository.WalletRepository;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class WalletService {

    private final WalletRepository walletRepository;
    private final boolean allowSeeding;

    public WalletService(WalletRepository walletRepository, @Value("${wallet.allow-seeding:false}") boolean allowSeeding) {
        this.walletRepository = walletRepository;
        this.allowSeeding = allowSeeding;
    }

    @Transactional
    public Wallet getOrCreate(String userId, long initialBalancePaise) {
        if (initialBalancePaise < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", "Initial balance must be non-negative");
        }
        if (initialBalancePaise > 0 && !allowSeeding) {
            throw new ApiException(HttpStatus.FORBIDDEN, "seeding_disabled", "Exercise seeding is disabled");
        }
        walletRepository.insertIfAbsent(UUID.randomUUID(), userId, initialBalancePaise);
        return walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("Wallet missing after get-or-create"));
    }

    public Wallet getOwnedWallet(UUID id, String userId) {
        Wallet wallet = walletRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "wallet_not_found", "Wallet not found"));
        if (!wallet.userId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "wallet_forbidden", "Wallet belongs to another user");
        }
        return wallet;
    }
}