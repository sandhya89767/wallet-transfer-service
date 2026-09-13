package com.paytm.wallet;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.domain.TransferStatus;
import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.service.TransferService;
import com.paytm.wallet.service.WalletService;
import com.paytm.wallet.api.ApiException;
import com.paytm.wallet.api.BearerTokens;
import com.paytm.wallet.repository.TransferRepository;
import com.paytm.wallet.repository.WalletRepository;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "wallet.auth-secret=integration-test-secret-not-for-production-123456",
    "wallet.allow-seeding=true",
    "logging.level.root=WARN"
})
@AutoConfigureMockMvc
@AutoConfigureObservability
@Testcontainers
@Timeout(90)
class WalletConcurrencyIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    @SuppressWarnings("unused")
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 40);
    }

    @Autowired
    private WalletService walletService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired private WalletRepository walletRepository;
    @Autowired private TransferRepository transferRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MockMvc mvc;
    @Autowired private BearerTokens tokens;

    @Test
    void concurrentGetOrCreateReturnsOneWallet() throws Exception {
        String userId = "wallet-race-" + UUID.randomUUID();
        List<Callable<Wallet>> requests = new ArrayList<>();
        for (int request = 0; request < 50; request++) {
            requests.add(() -> walletService.getOrCreate(userId, 10_000));
        }

        Set<UUID> walletIds = new HashSet<>(invokeConcurrently(requests).stream().map(Wallet::id).toList());
        Integer walletCount = jdbcClient.sql("SELECT COUNT(*) FROM wallets WHERE user_id = :userId")
                .param("userId", userId)
                .query(Integer.class)
                .single();

        assertThat(walletIds).hasSize(1);
        assertThat(walletCount).isEqualTo(1);
    }

    @Test
    void concurrentIdempotentRetriesMoveMoneyExactlyOnce() throws Exception {
        String sender = "retry-sender-" + UUID.randomUUID();
        String recipient = "retry-recipient-" + UUID.randomUUID();
        Wallet from = walletService.getOrCreate(sender, 100_000);
        Wallet to = walletService.getOrCreate(recipient, 20_000);
        String idempotencyKey = "retry-" + UUID.randomUUID();
        List<Callable<Transfer>> requests = new ArrayList<>();
        for (int request = 0; request < 30; request++) {
            requests.add(() -> transferService.create(sender, from.id(), to.id(), 7_500, idempotencyKey));
        }

        List<Transfer> responses = invokeConcurrently(requests);

        assertThat(responses).hasSize(30).allSatisfy(transfer -> assertThat(transfer).isEqualTo(responses.getFirst()));
        assertThat(responses).allMatch(transfer -> transfer.status() == TransferStatus.SUCCEEDED);
        assertThat(responses.stream().map(Transfer::id).collect(java.util.stream.Collectors.toSet())).hasSize(1);
        assertThat(walletService.getOwnedWallet(from.id(), sender).balancePaise()).isEqualTo(92_500);
        assertThat(walletService.getOwnedWallet(to.id(), recipient).balancePaise()).isEqualTo(27_500);
        assertThat(transferCount(idempotencyKey)).isEqualTo(1);
    }

    @Test
    void oppositeDirectionContentionConservesMoneyWithoutOverdraft() throws Exception {
        String firstUser = "contention-a-" + UUID.randomUUID();
        String secondUser = "contention-b-" + UUID.randomUUID();
        Wallet first = walletService.getOrCreate(firstUser, 100_000);
        Wallet second = walletService.getOrCreate(secondUser, 100_000);
        List<Callable<Transfer>> requests = new ArrayList<>();
        for (int request = 0; request < 400; request++) {
            int sequence = request;
            if (request % 2 == 0) {
                requests.add(() -> transferService.create(
                        firstUser, first.id(), second.id(), 100, "contention-" + first.id() + "-" + sequence));
            } else {
                requests.add(() -> transferService.create(
                        secondUser, second.id(), first.id(), 100, "contention-" + first.id() + "-" + sequence));
            }
        }

        List<Transfer> transfers = invokeConcurrently(requests);
        long firstBalance = walletService.getOwnedWallet(first.id(), firstUser).balancePaise();
        long secondBalance = walletService.getOwnedWallet(second.id(), secondUser).balancePaise();

        assertThat(transfers).allMatch(transfer -> transfer.status() == TransferStatus.SUCCEEDED);
        assertThat(firstBalance).isNotNegative();
        assertThat(secondBalance).isNotNegative();
        assertThat(firstBalance + secondBalance).isEqualTo(200_000);
    }

    @Test
    void foreignKeyLocksDoNotDeadlockWithWalletLocks() throws Exception {
        Wallet a = walletService.getOrCreate("lock-a-" + UUID.randomUUID(), 100);
        Wallet b = walletService.getOrCreate("lock-b-" + UUID.randomUUID(), 100);
        CountDownLatch inserted = new CountDownLatch(2);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            tasks.add(() -> new TransactionTemplate(transactionManager).execute(tx -> {
                transferRepository.tryInsert(UUID.randomUUID(), UUID.randomUUID().toString(), a.id(), b.id(), 1);
                inserted.countDown();
                try {
                    assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                assertThat(walletRepository.lockForTransfer(a.id(), b.id())).hasSize(2);
                tx.setRollbackOnly();
                return true;
            }));
        }
        assertThat(invokeConcurrently(tasks)).containsExactly(true, true);
    }

    @Test
    void concurrentDifferentBodiesConflictWithoutSecondMovement() throws Exception {
        Wallet a = walletService.getOrCreate("conflict-a-" + UUID.randomUUID(), 1000);
        Wallet b = walletService.getOrCreate("conflict-b-" + UUID.randomUUID(), 0);
        String key = UUID.randomUUID().toString();
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            long amount = i % 2 == 0 ? 10 : 20;
            tasks.add(() -> {
                try {
                    return transferService.create(a.userId(), a.id(), b.id(), amount, key).status().name();
                } catch (ApiException exception) {
                    assertThat(exception.status().value()).isEqualTo(409);
                    return "CONFLICT";
                }
            });
        }
        List<String> results = invokeConcurrently(tasks);
        assertThat(results.stream().filter("CONFLICT"::equals).count()).isEqualTo(15);
        assertThat(transferCount(key)).isEqualTo(1);
        long sent = transferRepository.findByIdempotencyKey(key).orElseThrow().amountPaise();
        assertThat(walletService.getOwnedWallet(a.id(), a.userId()).balancePaise()).isEqualTo(1000 - sent);
        assertThat(walletService.getOwnedWallet(b.id(), b.userId()).balancePaise()).isEqualTo(sent);
    }

    @Test
    void competingDebitsCannotOverdrawAndDeclineRemainsIdempotent() throws Exception {
        Wallet a = walletService.getOrCreate("drain-a-" + UUID.randomUUID(), 100);
        Wallet b = walletService.getOrCreate("drain-b-" + UUID.randomUUID(), 0);
        List<Callable<Transfer>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String key = UUID.randomUUID().toString();
            tasks.add(() -> transferService.create(a.userId(), a.id(), b.id(), 10, key));
        }
        List<Transfer> transfers = invokeConcurrently(tasks);
        assertThat(transfers.stream().filter(t -> t.status() == TransferStatus.SUCCEEDED).count()).isEqualTo(10);
        assertThat(transfers.stream().filter(t -> t.status() == TransferStatus.DECLINED).count()).isEqualTo(90);
        assertThat(walletService.getOwnedWallet(a.id(), a.userId()).balancePaise()).isZero();
        assertThat(walletService.getOwnedWallet(b.id(), b.userId()).balancePaise()).isEqualTo(100);
        Transfer declined = transfers.stream().filter(t -> t.status() == TransferStatus.DECLINED).findFirst().orElseThrow();
        transferService.create(b.userId(), b.id(), a.id(), 100, UUID.randomUUID().toString());
        assertThat(transferService.create(a.userId(), a.id(), b.id(), 10, declined.idempotencyKey())).isEqualTo(declined);
        assertThat(walletService.getOwnedWallet(a.id(), a.userId()).balancePaise()).isEqualTo(100);
    }

    @Test
    void failedCreditRollsBackDebitAndIdempotencyClaim() {
        Wallet a = walletService.getOrCreate("rollback-a-" + UUID.randomUUID(), 100);
        Wallet b = walletService.getOrCreate("rollback-b-" + UUID.randomUUID(), 0);
        String key = UUID.randomUUID().toString();
        // Deliberately fail the credit at the DB layer AFTER the debit, not via an application mock.
        jdbcClient.sql("ALTER TABLE wallets ADD CONSTRAINT test_credit_failure CHECK (id <> '" + b.id()
                + "'::uuid OR balance_paise <> 10)").update();
        try {
            assertThatThrownBy(() -> transferService.create(a.userId(), a.id(), b.id(), 10, key))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(walletService.getOwnedWallet(a.id(), a.userId()).balancePaise()).isEqualTo(100);
            assertThat(walletService.getOwnedWallet(b.id(), b.userId()).balancePaise()).isZero();
            assertThat(transferCount(key)).isZero();
        } finally {
            jdbcClient.sql("ALTER TABLE wallets DROP CONSTRAINT test_credit_failure").update();
        }
        assertThat(transferService.create(a.userId(), a.id(), b.id(), 10, key).status()).isEqualTo(TransferStatus.SUCCEEDED);
    }

    @Test
    void recipientOverflowDeclinesWithoutDebit() {
        Wallet a = walletService.getOrCreate("overflow-a-" + UUID.randomUUID(), 100);
        Wallet b = walletService.getOrCreate("overflow-b-" + UUID.randomUUID(), Long.MAX_VALUE);
        Transfer result = transferService.create(a.userId(), a.id(), b.id(), 1, UUID.randomUUID().toString());
        assertThat(result.status()).isEqualTo(TransferStatus.DECLINED);
        assertThat(result.failureReason()).isEqualTo("BALANCE_LIMIT_EXCEEDED");
        assertThat(walletService.getOwnedWallet(a.id(), a.userId()).balancePaise()).isEqualTo(100);
        assertThat(walletService.getOwnedWallet(b.id(), b.userId()).balancePaise()).isEqualTo(Long.MAX_VALUE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "1.0", "1e2", "\"10\"", "null", "0", "-1", "9223372036854775808"})
    void invalidMoneyIsRejectedByHttp(String amount) throws Exception {
        mvc.perform(post("/transfers").header("Authorization", authorization("validation-user"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"from":"%s","to":"%s","amount_paise":%s,"idempotency_key":"validation"}
                                """.formatted(UUID.randomUUID(), UUID.randomUUID(), amount)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test
    void authenticationOwnershipAndErrorsAreEnforced() throws Exception {
        mvc.perform(post("/wallets").header("Authorization", authorization("empty-body-" + UUID.randomUUID()))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.balance_paise").value(0));
        Wallet a = walletService.getOrCreate("http-a-" + UUID.randomUUID(), 100);
        Wallet b = walletService.getOrCreate("http-b-" + UUID.randomUUID(), 0);
        mvc.perform(post("/wallets")).andExpect(status().isUnauthorized());
        mvc.perform(post("/wallets").header("Authorization", "Bearer " + a.userId())).andExpect(status().isUnauthorized());
        mvc.perform(post("/wallets").header("Authorization", "Bearer " + tokens.issue(a.userId(), Instant.now().minusSeconds(1))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/wallets/" + a.id()).header("Authorization", authorization(b.userId())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/wallets/not-a-uuid").header("Authorization", authorization(a.userId())))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/wallets/" + UUID.randomUUID()).header("Authorization", authorization(a.userId())))
                .andExpect(status().isNotFound());
        String key = UUID.randomUUID().toString();
        String body = """
                {"from":"%s","to":"%s","amount_paise":10,"idempotency_key":"%s"}
                """.formatted(a.id(), b.id(), key);
        mvc.perform(post("/transfers").header("Authorization", authorization(b.userId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        assertThat(transferCount(key)).isZero();
        String original = mvc.perform(post("/transfers").header("Authorization", authorization(a.userId()))
                        .header("X-Correlation-Id", "audit-http-request").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(header().string("X-Correlation-Id", "audit-http-request"))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post("/transfers").header("Authorization", authorization(a.userId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(content().string(original));
        mvc.perform(post("/transfers").header("Authorization", authorization(a.userId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body.replace(":10", ":20")))
                .andExpect(status().isConflict());
        UUID transferId = transferRepository.findByIdempotencyKey(key).orElseThrow().id();
        mvc.perform(get("/transfers/" + transferId).header("Authorization", authorization(b.userId())))
                .andExpect(status().isOk());
        mvc.perform(get("/transfers/" + transferId).header("Authorization", authorization("unrelated-user")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/transfers").header("Authorization", authorization(a.userId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body.replace(b.id().toString(), UUID.randomUUID().toString())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("wallet_transfers_recorded_total")));
    }

    private String authorization(String user) {
        return "Bearer " + tokens.issue(user, Instant.now().plusSeconds(3600));
    }

    private int transferCount(String idempotencyKey) {
        return jdbcClient.sql("SELECT COUNT(*) FROM transfers WHERE idempotency_key = :key")
                .param("key", idempotencyKey)
                .query(Integer.class)
                .single();
    }

    private <T> List<T> invokeConcurrently(List<Callable<T>> tasks) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = executor.invokeAll(tasks);
            List<T> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }
}