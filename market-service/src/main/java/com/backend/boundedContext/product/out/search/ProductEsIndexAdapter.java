package com.backend.boundedContext.product.out.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * ES 상품 색인. 검색과 변경 반영은 alias({@code products})로 하고, 실제 인덱스는 {@code products_{만든 시각}}이다.
 * 매핑을 바꿀 때는 전체 재색인으로 새 인덱스를 채운 뒤 alias만 옮긴다.
 * <p>
 * alias가 없으면 색인에 쓰지 않고 예외를 던진다. 빈 인덱스를 만들어 붙이면 검색이 실패하지 않고 방금 색인한 몇 개만 돌려줘
 * DB 검색으로 넘어가지도 않기 때문이다 (실패보다 "틀린 결과로 성공"이 더 위험하다). alias는 전체 재색인이 만들고,
 * alias가 없으면 기동 직후와 그 뒤 주기마다 자동으로 전체 재색인한다(ProductSearchIndexRecoveryScheduler).
 * 쓰기 요청에는 require_alias를 건다. 앱이 떠 있는 동안 ES 데이터가 지워져도, ES가 매핑 없는 {@code products} 인덱스를 자동으로 만들지 않고 거부한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "custom.product.search.engine", havingValue = "es")
public class ProductEsIndexAdapter implements ProductSearchIndexPort {
    private static final String INDEX_SETTINGS = "elasticsearch/products-index.json";
    private static final DateTimeFormatter INDEX_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final ElasticsearchClient client;
    private final String alias;
    private volatile boolean aliasReady;

    public ProductEsIndexAdapter(ElasticsearchClient client,
                                 @Value("${custom.product.search.indexAlias}") String alias) {
        this.client = client;
        this.alias = alias;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean aliasExists() {
        try {
            return client.indices().existsAlias(e -> e.name(alias)).value();
        } catch (IOException e) {
            throw new UncheckedIOException("상품 검색 alias를 확인하지 못했습니다. alias=" + alias, e);
        }
    }

    @Override
    public void upsert(List<ProductSearchDocument> documents) {
        if (documents.isEmpty()) return;
        ensureAlias();
        BulkRequest.Builder bulk = new BulkRequest.Builder().requireAlias(true);
        documents.forEach(doc -> bulk.operations(op -> op.index(i -> i
                .index(alias)
                .id(String.valueOf(doc.productId()))
                .document(doc))));
        execute(bulk.build());
    }

    @Override
    public void delete(Collection<Long> productIds) {
        if (productIds.isEmpty()) return;
        ensureAlias();

        BulkRequest.Builder bulk = new BulkRequest.Builder().requireAlias(true);
        productIds.forEach(id -> bulk.operations(op -> op.delete(d -> d.index(alias).id(String.valueOf(id)))));
        execute(bulk.build());
    }

    @Override
    public String createIndex() {
        String indexName = alias + "_" + LocalDateTime.now().format(INDEX_SUFFIX);
        try (InputStream settings = new ClassPathResource(INDEX_SETTINGS).getInputStream()) {
            client.indices().create(c -> c.index(indexName).withJson(settings));
        } catch (IOException e) {
            throw new UncheckedIOException("상품 검색 인덱스를 만들지 못했습니다. index=" + indexName, e);
        }
        return indexName;
    }

    @Override
    public void bulkIndex(String indexName, List<ProductSearchDocument> documents) {
        if (documents.isEmpty()) return;
        // 재색인 중 인덱스가 지워졌으면 쓰지 않는다. 그대로 쓰면 ES가 같은 이름으로 매핑 없는(nori 없는) 인덱스를 자동으로 만들고,
        // 이어지는 alias 전환이 그 인덱스로 검색을 옮긴다. 이름이 있는 인덱스라 require_alias를 쓸 수 없어 묶음마다 확인한다
        if (!indexExists(indexName)) {
            throw new IllegalStateException("재색인 중인 상품 검색 인덱스가 없어 색인하지 않습니다. index=" + indexName);
        }

        BulkRequest.Builder bulk = new BulkRequest.Builder();
        documents.forEach(doc -> bulk.operations(op -> op.index(i -> i
                .index(indexName)
                .id(String.valueOf(doc.productId()))
                .document(doc))));
        execute(bulk.build());
    }

    /**
     * 새 인덱스를 refresh한 뒤 alias를 옮긴다. bulk로 넣은 문서는 refresh 전까지 검색되지 않으므로,
     * 먼저 옮기면 잠깐 동안 검색이 오류 없이 빈 결과를 돌려준다 (DB 검색으로 넘어가지도 않는다).
     */
    @Override
    public void switchAlias(String indexName) {
        try {
            client.indices().refresh(r -> r.index(indexName));
            Set<String> previous = currentIndices();
            client.indices().updateAliases(u -> {
                u.actions(a -> a.add(add -> add.index(indexName).alias(alias)));
                previous.forEach(old -> u.actions(a -> a.remove(r -> r.index(old).alias(alias))));
                return u;
            });
            aliasReady = true;
            if (!previous.isEmpty()) {
                client.indices().delete(d -> d.index(List.copyOf(previous)));
            }
            log.info("상품 검색 alias를 옮겼습니다. alias={}, index={}, 지운 인덱스={}", alias, indexName, previous);
        } catch (IOException e) {
            throw new UncheckedIOException("상품 검색 alias를 옮기지 못했습니다. index=" + indexName, e);
        }
    }

    /**
     * alias가 없으면 쓰지 않고 예외를 던진다. 있으면 다시 묻지 않는다
     * (그 뒤에 alias가 사라지면 require_alias가 쓰기를 거부한다).
     */
    private void ensureAlias() {
        if (aliasReady) return;
        if (!aliasExists()) {
            throw new IllegalStateException("상품 검색 alias가 없어 색인하지 않습니다. 전체 재색인이 alias를 만든 뒤 반영합니다. alias=" + alias);
        }
        aliasReady = true;
    }

    private boolean indexExists(String indexName) {
        try {
            return client.indices().exists(e -> e.index(indexName)).value();
        } catch (IOException e) {
            throw new UncheckedIOException("상품 검색 인덱스를 확인하지 못했습니다. index=" + indexName, e);
        }
    }

    private Set<String> currentIndices() throws IOException {
        if (!client.indices().existsAlias(e -> e.name(alias)).value()) return Set.of();
        return client.indices().getAlias(g -> g.name(alias)).aliases().keySet();
    }

    private void execute(BulkRequest request) {
        try {
            BulkResponse response = client.bulk(request);
            if (response.errors()) {
                List<String> failures = response.items().stream()
                        .filter(item -> item.error() != null)
                        .map(ProductEsIndexAdapter::describe)
                        .toList();
                if (!failures.isEmpty()) {
                    throw new IllegalStateException("상품 검색 색인 일부를 반영하지 못했습니다. " + failures);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("상품 검색 색인에 접속하지 못했습니다.", e);
        }
    }

    private static String describe(BulkResponseItem item) {
        return item.id() + ":" + item.error().type() + ":" + item.error().reason();
    }
}
