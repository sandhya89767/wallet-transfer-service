package com.paytm.wallet.repository;

import com.paytm.wallet.domain.Wallet;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class WalletRepository {

    private final JdbcClient jdbcClient;

    public WalletRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void insertIfAbsent(UUID id, String userId, long initialBalancePaise) {
        jdbcClient.sql("""
                        INSERT INTO wallets (id, user_id, balance_paise)
                        VALUES (:id, :userId, :balance)
                        ON CONFLICT (user_id) DO NOTHING
                        """)
                .param("id", id)
                .param("userId", userId)
                .param("balance", initialBalancePaise)
                .update();
    }

    public Optional<Wallet> findByUserId(String userId) {
        return jdbcClient.sql("""
                        SELECT id, user_id, balance_paise, created_at
                        FROM wallets
                        WHERE user_id = :userId
                        """)
                .param("userId", userId)
                .query((resultSet, rowNumber) -> mapWallet(resultSet))
                .optional();
    }

    public Optional<Wallet> findById(UUID id) {
        return jdbcClient.sql("""
                        SELECT id, user_id, balance_paise, created_at
                        FROM wallets
                        WHERE id = :id
                        """)
                .param("id", id)
                .query((resultSet, rowNumber) -> mapWallet(resultSet))
                .optional();
    }

    public List<Wallet> lockForTransfer(UUID firstId, UUID secondId) {
        return jdbcClient.sql("""
                        SELECT id, user_id, balance_paise, created_at
                        FROM wallets
                        WHERE id IN (:firstId, :secondId)
                        ORDER BY id
                        FOR NO KEY UPDATE
                        """)
                .param("firstId", firstId)
                .param("secondId", secondId)
                .query((resultSet, rowNumber) -> mapWallet(resultSet))
                .list();
    }

    public boolean debitIfSufficient(UUID id, long amountPaise) {
        int changed = jdbcClient.sql("""
                        UPDATE wallets
                        SET balance_paise = balance_paise - :amount, updated_at = NOW()
                        WHERE id = :id AND balance_paise >= :amount
                        """)
                .param("id", id)
                .param("amount", amountPaise)
                .update();
        return changed == 1;
    }

    public void credit(UUID id, long amountPaise) {
        int changed = jdbcClient.sql("""
                        UPDATE wallets
                        SET balance_paise = balance_paise + :amount, updated_at = NOW()
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("amount", amountPaise)
                .update();
        if (changed != 1) {
            throw new IllegalStateException("Credit target disappeared while locked");
        }
    }

    private Wallet mapWallet(ResultSet resultSet) throws SQLException {
        return new Wallet(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("user_id"),
                resultSet.getLong("balance_paise"),
                resultSet.getTimestamp("created_at").toInstant());
    }
}