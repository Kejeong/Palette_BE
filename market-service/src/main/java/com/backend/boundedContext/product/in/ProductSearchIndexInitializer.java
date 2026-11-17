package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.app.ProductFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 기동이 끝난 뒤 상품 검색 alias가 없으면 전체 재색인한다 (engine=es일 때만).
 * 첫 배포나 ES 데이터를 다시 만든 뒤 사람이 관리자 재색인을 부르지 않아도 검색이 채워진다.
 * 관리자 재색인 API는 매핑을 바꿀 때와 복구할 때만 쓴다.
 * <p>
 * 오래 걸릴 수 있어 준비 완료를 늦추지 않게 별도 스레드에서 실행한다. 재색인이 끝나기 전에는 alias가 없어 검색은 DB 검색으로 대신한다.
 * 실패하면 ERROR로 남겨 알림이 가게 한다. ES가 꺼져 있어도 market은 계속 뜬다.
 * market이 한 대라는 전제다. 여러 대가 동시에 뜨면 각자 인덱스를 만들 수 있으니, 그때는 PostgreSQL advisory lock으로 한 대만 실행하게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "custom.product.search.engine", havingValue = "es")
public class ProductSearchIndexInitializer {
    private final ProductFacade productFacade;

    @EventListener(ApplicationReadyEvent.class)
    public void rebuildIfMissing() {
        Thread.ofVirtual().name("product-search-index-init").start(this::run);
    }

    void run() {
        try {
            productFacade.rebuildSearchIndexIfMissing().ifPresent(count ->
                    log.info("상품 검색 alias가 없어 전체 재색인했습니다. 상품 {}개", count));
        } catch (Exception e) {
            log.error("상품 검색 alias가 없는데 전체 재색인에 실패했습니다. ES 상태를 확인한 뒤 관리자 재색인을 호출해 주세요.", e);
        }
    }
}
