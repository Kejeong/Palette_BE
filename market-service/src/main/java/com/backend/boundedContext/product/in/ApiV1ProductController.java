package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.boundedContext.product.app.dto.PopularKeywordDto;
import com.backend.boundedContext.product.app.dto.ProductDetailDto;
import com.backend.boundedContext.product.app.dto.ProductSummaryDto;
import com.backend.boundedContext.product.domain.ProductSort;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "상품", description = "상품 검색·인기 검색어·상세 (로그인 불필요)")
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ApiV1ProductController {
    private final ProductFacade productFacade;

    @Operation(
            summary = "상품 검색",
            description = "판매 중이고 사용 중인 카테고리의 상품을 검색합니다. 상품명은 형태소(nori)로 찾습니다. size는 최대 50입니다. "
                    + "검색 서버가 응답하지 않으면 DB 검색으로 대신하며, 이때 관련도순은 최신순, 가격순은 정가 기준입니다. "
                    + "할인 시작·종료 직후 약 10초 동안은 특가 필터·할인율순·가격순이 이전 상태로 보일 수 있습니다(화면 가격은 정확). "
                    + "검색 색인이 잠깐 늦어 보여 줄 수 없는 상품은 빠지므로 한 페이지가 size보다 적을 수 있습니다.")
    @GetMapping
    public RsData<PageResponse<ProductSummaryDto>> searchProducts(
            @Parameter(description = "상품명 일부 (최대 100자)")
            @RequestParam(required = false) @Size(max = 100) String keyword,
            @Parameter(description = "대분류 ID면 그 아래 소분류 전체, 소분류 ID면 그 소분류만")
            @RequestParam(required = false) Long categoryId,
            @Parameter(description = "RELEVANCE(관련도순) · LATEST(최신순) · PRICE_ASC · PRICE_DESC(가격순, 할인 중이면 할인가 기준) · "
                    + "DISCOUNT_RATE(할인율 높은순, 할인 없는 상품은 뒤). "
                    + "보내지 않으면 검색어가 있을 때 RELEVANCE, 없을 때 LATEST. 검색어 없이 RELEVANCE면 LATEST로 처리")
            @RequestParam(required = false) ProductSort sort,
            @Parameter(description = "true면 품절 상품 제외")
            @RequestParam(defaultValue = "true") boolean excludeSoldOut,
            @Parameter(description = "true면 지금 할인 중인 상품만 (특가)")
            @RequestParam(defaultValue = "false") boolean discounted,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "상품 목록을 조회했습니다.",
                productFacade.searchProducts(keyword, categoryId, sort, excludeSoldOut, discounted, pageable));
    }

    @Operation(
            summary = "인기 검색어",
            description = "최근 24시간 동안 많이 검색한 검색어 상위 10개입니다. 결과가 있었던 첫 페이지 검색만 셉니다. "
                    + "rankChange는 한 시간 전 순위와의 차이이고, 새로 오른 검색어는 null입니다. "
                    + "1분마다 갱신하며, 집계 저장소가 응답하지 않으면 빈 목록입니다.")
    @GetMapping("/popular-keywords")
    public RsData<List<PopularKeywordDto>> getPopularKeywords() {
        return RsData.of("200-1", "인기 검색어를 조회했습니다.", productFacade.getPopularKeywords());
    }

    @Operation(
            summary = "상품 상세",
            description = "판매 중지·삭제된 상품과 사용 중지된 카테고리의 상품은 404입니다. 재고가 5개 이하인 옵션만 남은 수량을 줍니다. "
                    + "로그인 토큰을 보내면 wished에 찜 여부를 채우고, 없으면 false입니다.")
    @GetMapping("/{productId}")
    public RsData<ProductDetailDto> getProductDetail(
            @PathVariable Long productId,
            @AuthenticationPrincipal AuthMember authMember   // 로그인하지 않았으면 null
    ) {
        Long memberId = authMember == null ? null : authMember.memberId();
        return RsData.of("200-1", "상품을 조회했습니다.", productFacade.getProductDetail(productId, memberId));
    }
}
