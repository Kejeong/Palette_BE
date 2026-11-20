package com.backend.boundedContext.payment.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.boundedContext.payment.domain.Wallet;

/**
 * 지갑 생성(insertIfAbsent)을 같은 회원으로 여러 번·동시에 불러도 지갑이 하나만 남는지 확인한다.
 * 로컬 Docker PostgreSQL이 필요하다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")   // 테스트 중 스케줄러가 테스트 데이터를 바꾸지 않게 끔
class WalletRepositoryInsertIfAbsentTest {

    private static final int THREADS = 10;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    // 다른 테스트·시드 회원과 겹치지 않는 회원 ID
    private final Long memberId = ThreadLocalRandom.current().nextLong(7_000_000_000L, 8_000_000_000L);

    @AfterEach
    void cleanUp() {
        walletRepository.findByMemberId(memberId).ifPresent(walletRepository::delete);
    }

    @Test
    @DisplayName("지갑이 없으면 잔액 0인 지갑을 만들고 1, 같은 회원으로 다시 부르면 만들지 않고 0")
    void insertIfAbsent_twice() {
        assertThat(insert()).isEqualTo(1);
        assertThat(insert()).isZero();

        Wallet wallet = walletRepository.findByMemberId(memberId).orElseThrow();
        assertThat(wallet.getBalance()).isZero();
        assertThat(wallet.getCreatedAt()).isNotNull();
        assertThat(wallet.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("같은 회원으로 동시에 10번 불러도 예외 없이 한 번만 만들고 지갑은 하나")
    void insertIfAbsent_concurrent() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            Callable<Integer> task = () -> {
                start.await();
                return insert();
            };
            results.add(executor.submit(task));
        }
        start.countDown();

        int created = 0;
        for (Future<Integer> result : results) {
            created += result.get(10, TimeUnit.SECONDS);   // 유니크 위반이 나면 여기서 예외
        }
        executor.shutdown();

        assertThat(created).isEqualTo(1);
        assertThat(walletRepository.findAll()).filteredOn(wallet -> wallet.getMemberId().equals(memberId)).hasSize(1);
    }

    private int insert() {
        return transactionTemplate.execute(status -> walletRepository.insertIfAbsent(memberId, LocalDateTime.now()));
    }
}
