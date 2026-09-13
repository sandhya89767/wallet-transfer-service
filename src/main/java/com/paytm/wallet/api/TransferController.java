package com.paytm.wallet.api;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.domain.TransferStatus;
import com.paytm.wallet.service.TransferService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public TransferResponse create(
            @RequestAttribute("userId") String userId,
            @Valid @RequestBody CreateTransferRequest request) {
        Transfer transfer = transferService.create(
                userId,
                request.from(),
                request.to(),
                request.amountPaise(),
                request.idempotencyKey());
        return TransferResponse.from(transfer);
    }

    @GetMapping("/{id}")
    public TransferResponse get(
            @RequestAttribute("userId") String userId,
            @PathVariable UUID id) {
        return TransferResponse.from(transferService.get(id, userId));
    }

    public record CreateTransferRequest(
            @NotNull UUID from,
            @NotNull UUID to,
            @Positive long amountPaise,
            @NotBlank @Size(max = 200) String idempotencyKey) {
    }

    public record TransferResponse(
            UUID id,
            UUID from,
            UUID to,
            long amountPaise,
            TransferStatus status,
            String failureReason,
            Instant createdAt,
            Instant updatedAt) {

        public static TransferResponse from(Transfer transfer) {
            return new TransferResponse(
                    transfer.id(),
                    transfer.fromWalletId(),
                    transfer.toWalletId(),
                    transfer.amountPaise(),
                    transfer.status(),
                    transfer.failureReason(),
                    transfer.createdAt(),
                    transfer.updatedAt());
        }
    }
}