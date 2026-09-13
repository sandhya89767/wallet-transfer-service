package com.paytm.wallet.repository;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.domain.TransferStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TransferRepository {

    private final JdbcClient jdbcClient;

    public TransferRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public boolean tryInsert(UUID id, String idempotencyKey, UUID from, UUID to, long amountPaise) {
        int changed = jdbcClient.sql("""
                        INSERT INTO transfers (
                            id, idempotency_key, from_wallet_id, to_wallet_id, amount_paise, status
                        ) VALUES (
                            :id, :idempotencyKey, :fromId, :toId, :amount, 'PENDING'
                        )
                        ON CONFLICT (idempotency_key) DO NOTHING
                        """)
                .param("id", id)
                .param("idempotencyKey", idempotencyKey)
                .param("fromId", from)
                .param("toId", to)
                .param("amount", amountPaise)
                .update();
        return changed == 1;
    }

    public Optional<Transfer> findByIdempotencyKey(String idempotencyKey) {
        return jdbcClient.sql(transferSelect() + " WHERE idempotency_key = :idempotencyKey")
                .param("idempotencyKey", idempotencyKey)
                .query((resultSet, rowNumber) -> mapTransfer(resultSet))
                .optional();
    }

    public Optional<Transfer> findByIdForUser(UUID id, String userId) {
        return jdbcClient.sql(transferSelect() + """
                         WHERE t.id = :id
                           AND EXISTS (
                               SELECT 1
                               FROM wallets w
                               WHERE w.id IN (t.from_wallet_id, t.to_wallet_id)
                                 AND w.user_id = :userId
                           )
                        """)
                .param("id", id)
                .param("userId", userId)
                .query((resultSet, rowNumber) -> mapTransfer(resultSet))
                .optional();
    }

    public void markSucceeded(UUID id) {
        updateStatus(id, TransferStatus.SUCCEEDED, null);
    }

    public void markDeclined(UUID id, String reason) {
        updateStatus(id, TransferStatus.DECLINED, reason);
    }

    private void updateStatus(UUID id, TransferStatus status, String reason) {
        int changed = jdbcClient.sql("""
                        UPDATE transfers
                        SET status = :status, failure_reason = :reason, updated_at = NOW()
                        WHERE id = :id AND status = 'PENDING'
                        """)
                .param("id", id)
                .param("status", status.name())
                .param("reason", reason)
                .update();
        if (changed != 1) {
            throw new IllegalStateException("Transfer status changed unexpectedly");
        }
    }

    private String transferSelect() {
        return """
                SELECT t.id, t.idempotency_key, t.from_wallet_id, t.to_wallet_id,
                       t.amount_paise, t.status, t.failure_reason, t.created_at, t.updated_at
                FROM transfers t
                """;
    }

    private Transfer mapTransfer(ResultSet resultSet) throws SQLException {
        return new Transfer(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("idempotency_key"),
                resultSet.getObject("from_wallet_id", UUID.class),
                resultSet.getObject("to_wallet_id", UUID.class),
                resultSet.getLong("amount_paise"),
                TransferStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("failure_reason"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }
}