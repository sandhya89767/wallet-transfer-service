package com.paytm.wallet.api;

import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.service.WalletService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping
    public ResponseEntity<WalletResponse> getOrCreate(
            @RequestAttribute("userId") String userId,
            @Valid @RequestBody(required = false) CreateWalletRequest request) {
        Long seed = request == null ? null : request.initialBalancePaise();
        long initialBalance = seed == null ? 0L : seed;
        Wallet wallet = walletService.getOrCreate(userId, initialBalance);
        return ResponseEntity.created(URI.create("/wallets/" + wallet.id()))
                .body(WalletResponse.from(wallet));
    }

    @GetMapping("/{id}")
    public WalletResponse get(
            @RequestAttribute("userId") String userId,
            @PathVariable UUID id) {
        return WalletResponse.from(walletService.getOwnedWallet(id, userId));
    }

    public record CreateWalletRequest(@PositiveOrZero Long initialBalancePaise) {
    }

    public record WalletResponse(UUID id, String userId, long balancePaise) {

        public static WalletResponse from(Wallet wallet) {
            return new WalletResponse(wallet.id(), wallet.userId(), wallet.balancePaise());
        }
    }
}