package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.CategoryTreeDto;
import com.backend.boundedContext.product.app.dto.ProductOptionSpec;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.global.outbox.OutboxEvent;
import com.backend.global.outbox.OutboxEventRepository;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.out.OrderApi;
import com.backend.shared.product.event.ProductChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Comparator;
import java.util.List;

import static com.backend.boundedContext.product.ProductImageFixtures.THUMBNAIL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 상품 변경 이벤트(ProductChangedEvent)가 상품을 바꾼 트랜잭션에서 outbox에 저장되는지.
 * 카테고리는 마이그레이션이 넣은 기본 카테고리를 쓴다. 테스트마다 롤백된다.
 */
@SpringBootTest
@Transactional
class ProductChangeEventTest {

    private static final long MEMBER_ID = 100L;
    private static final long SELLER_ID = 200L;
    private static final String EVENT_TYPE = "ProductChangedEvent";

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MemberApi memberApi;

    /** 다른 상품 통합 테스트와 mock 조합을 맞춰 테스트 컨텍스트를 같이 쓴다 */
    @MockitoBean
    private OrderApi orderApi;

    @BeforeEach
    void setUp() {
        given(memberApi.getApprovedSellerByMemberId(MEMBER_ID))
                .willReturn(new SellerDto(SELLER_ID, MEMBER_ID, "팔레트 상점", SellerStatus.APPROVED));
    }

    @Test
    @DisplayName("뷰티 상품 등록: 이름·설명·소분류·전성분·정가·대표 이미지·판매 중 여부를 담아 product.events로 발행한다")
    void create() {
        Long productId = createBeauty("수분 크림", "정제수, 나이아신아마이드");

        ProductChangedEvent event = lastEvent(productId);
        OutboxEvent outbox = events(productId).getLast();
        assertThat(outbox.getAggregateType()).isEqualTo("PRODUCT");
        assertThat(outbox.getAggregateId()).isEqualTo(productId);
        assertThat(event.name()).isEqualTo("수분 크림");
        assertThat(event.description()).isEqualTo("설명");
        assertThat(event.categoryName()).isEqualTo("스킨케어");
        assertThat(event.beauty()).isTrue();
        assertThat(event.ingredientsText()).isEqualTo("정제수, 나이아신아마이드");
        assertThat(event.price()).isEqualTo(25_000);
        assertThat(event.thumbnailUrl()).isNotBlank();
        assertThat(event.onSale()).isTrue();
    }

    @Test
    @DisplayName("의류 상품 등록: beauty=false, 전성분 null")
    void createClothing() {
        Long productId = productFacade.createProduct(MEMBER_ID, categoryId("의류", "상의"), "티셔츠", "설명", 20_000,
                List.of(), List.of(ProductOptionSpec.single(10)), THUMBNAIL, List.of(), "정제수");

        ProductChangedEvent event = lastEvent(productId);
        assertThat(event.beauty()).isFalse();
        assertThat(event.ingredientsText()).isNull();
    }

    @Test
    @DisplayName("수정하면 바뀐 값으로, 삭제하면 onSale=false로 한 번씩 더 발행한다")
    void modifyAndDelete() {
        Long productId = createBeauty("수분 크림", "정제수");

        productFacade.modifyProduct(MEMBER_ID, productId, "진정 크림", null, null, null, null, null, List.of(), "정제수, 병풀추출물");
        assertThat(lastEvent(productId).name()).isEqualTo("진정 크림");
        assertThat(lastEvent(productId).ingredientsText()).isEqualTo("정제수, 병풀추출물");

        productFacade.deleteProduct(MEMBER_ID, productId);
        assertThat(lastEvent(productId).onSale()).isFalse();
        assertThat(events(productId)).hasSize(3);
    }

    @Test
    @DisplayName("관리자 판매 중지는 onSale=false로 발행한다")
    void stopByAdmin() {
        Long productId = createBeauty("수분 크림", "정제수");

        productFacade.stopProductByAdmin(productId, "부적절한 상품");

        assertThat(lastEvent(productId).onSale()).isFalse();
    }

    @Test
    @DisplayName("재고만 바꾸면 상품 변경 이벤트를 발행하지 않는다")
    void stockChangeDoesNotPublish() {
        Long productId = createBeauty("수분 크림", "정제수");
        ProductOption option = productOptionRepository.findAll().stream()
                .filter(o -> o.getProduct().getId().equals(productId)).findFirst().orElseThrow();

        productFacade.adjustStock(MEMBER_ID, productId, option.getId(), 5);

        assertThat(events(productId)).hasSize(1);   // 등록 때 한 번뿐
    }

    @Test
    @DisplayName("전체 재발행: 판매 중인 상품만 한 번씩 발행하고 그 수를 돌려준다")
    void publishAll() {
        Long onSale = createBeauty("수분 크림", "정제수");
        Long stopped = createBeauty("진정 크림", "정제수");
        productFacade.stopProductByAdmin(stopped, "사유");
        int before = events(onSale).size();

        int published = productFacade.publishAllProductChanges();

        assertThat(published).isGreaterThanOrEqualTo(1);
        assertThat(events(onSale)).hasSize(before + 1);
        assertThat(events(stopped)).hasSize(2);   // 등록·중지 때뿐, 재발행에는 빠진다
    }

    private Long createBeauty(String name, String ingredientsText) {
        return productFacade.createProduct(MEMBER_ID, categoryId("뷰티", "스킨케어"), name, "설명", 25_000,
                List.of(), List.of(ProductOptionSpec.single(10)), THUMBNAIL, List.of(), ingredientsText);
    }

    private List<OutboxEvent> events(Long productId) {
        return outboxEventRepository.findAll().stream()
                .filter(e -> e.getEventType().equals(EVENT_TYPE) && e.getAggregateId().equals(productId))
                .sorted(Comparator.comparing(OutboxEvent::getId))
                .toList();
    }

    private ProductChangedEvent lastEvent(Long productId) {
        return objectMapper.readValue(events(productId).getLast().getPayload(), ProductChangedEvent.class);
    }

    private Long categoryId(String parentName, String childName) {
        CategoryTreeDto parent = productFacade.getCategoryTree().stream()
                .filter(c -> c.name().equals(parentName)).findFirst().orElseThrow();
        return parent.children().stream().filter(c -> c.name().equals(childName)).findFirst().orElseThrow().categoryId();
    }
}
