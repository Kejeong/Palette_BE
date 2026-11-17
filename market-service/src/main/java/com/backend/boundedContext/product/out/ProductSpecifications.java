package com.backend.boundedContext.product.out;

import com.backend.boundedContext.product.domain.OptionStock;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductDiscount;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Locale;

/** 상품 목록 조회 조건 (구매자 검색, 판매자 상품 목록) */
public final class ProductSpecifications {
    private static final char LIKE_ESCAPE = '\\';

    private ProductSpecifications() {
    }

    public static Specification<Product> statusEquals(ProductSaleStatus status) {
        return (root, q, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Product> statusNotEquals(ProductSaleStatus status) {
        return (root, q, cb) -> cb.notEqual(root.get("status"), status);
    }

    public static Specification<Product> sellerEquals(Long sellerId) {
        return (root, q, cb) -> cb.equal(root.get("sellerId"), sellerId);
    }

    public static Specification<Product> categoryIn(Collection<Long> categoryIds) {
        return (root, q, cb) -> root.get("category").get("id").in(categoryIds);
    }

    /** 상품명 부분 일치(대소문자 무시). null이면 조건 없음. %, _ 는 글자 그대로 찾는다. */
    public static Specification<Product> nameContains(String keyword) {
        if (keyword == null) return Specification.unrestricted();
        String pattern = "%" + escapeLike(keyword.toLowerCase(Locale.ROOT)) + "%";
        return (root, q, cb) -> cb.like(cb.lower(root.get("name")), pattern, LIKE_ESCAPE);
    }

    /** 삭제되지 않은 옵션 중 재고가 남은 것이 하나라도 있는 상품 */
    public static Specification<Product> inStock() {
        return (root, q, cb) -> {
            Subquery<Long> sub = q.subquery(Long.class);
            Root<OptionStock> stock = sub.from(OptionStock.class);
            Join<OptionStock, ProductOption> option = stock.join("option");
            sub.select(stock.get("id")).where(
                    cb.equal(option.get("product"), root),
                    cb.isNull(option.get("deletedAt")),
                    cb.greaterThan(stock.get("stock"), 0));
            return cb.exists(sub);
        };
    }

    /** 품절 상품 (삭제되지 않은 옵션의 재고가 모두 0) */
    /** 그 시각에 진행 중인 할인(삭제 안 됨, 시작 ≤ 시각 < 종료)이 있는 상품 */
    public static Specification<Product> discountedAt(LocalDateTime now) {
        return (root, q, cb) -> {
            Subquery<Long> sub = q.subquery(Long.class);
            Root<ProductDiscount> discount = sub.from(ProductDiscount.class);
            sub.select(discount.get("id")).where(
                    cb.equal(discount.get("product"), root),
                    cb.isNull(discount.get("deletedAt")),
                    cb.lessThanOrEqualTo(discount.get("startsAt"), now),
                    cb.greaterThan(discount.get("endsAt"), now));
            return cb.exists(sub);
        };
    }

    public static Specification<Product> soldOut() {
        return Specification.not(inStock());
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
