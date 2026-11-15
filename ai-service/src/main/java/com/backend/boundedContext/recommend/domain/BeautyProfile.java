package com.backend.boundedContext.recommend.domain;

import com.backend.global.exception.DomainException;
import com.backend.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 회원의 뷰티 프로필(설문 답변). 회원당 1개이고 다시 저장하면 덮어쓴다.
 * 값은 코드표(enum) 이름으로만 저장하고 한글 라벨은 저장하지 않는다.
 * 여러 개인 답은 text[]로 둔다. 추천 SQL이 배열 연산자(&&)로 피하는 성분을 바로 거른다.
 */
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "beauty_profile")
public class BeautyProfile extends BaseIdAndTime {

    public static final int MAX_CONCERNS = 3;

    @Getter
    @Column(nullable = false, unique = true)
    private Long memberId;

    @Getter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SkinType skinType;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private String[] concerns;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private String[] preferredIngredients;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private String[] avoidedIngredients;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private String[] interestCategories;

    private BeautyProfile(Long memberId) {
        this.memberId = memberId;
    }

    // 피하는 성분이 없으면("없어요") 빈 목록
    public static BeautyProfile create(Long memberId, SkinType skinType, List<SkinConcern> concerns,
                                       List<IngredientTag> preferredIngredients, List<IngredientTag> avoidedIngredients,
                                       List<BeautyCategory> interestCategories) {
        BeautyProfile profile = new BeautyProfile(memberId);
        profile.update(skinType, concerns, preferredIngredients, avoidedIngredients, interestCategories);
        return profile;
    }

    public void update(SkinType skinType, List<SkinConcern> concerns, List<IngredientTag> preferredIngredients,
                       List<IngredientTag> avoidedIngredients, List<BeautyCategory> interestCategories) {
        if (concerns.size() > MAX_CONCERNS) throw invalid();
        checkSide(preferredIngredients, IngredientTag.Side.PREFERRED);
        checkSide(avoidedIngredients, IngredientTag.Side.AVOIDED);
        this.skinType = skinType;
        this.concerns = codes(concerns, SkinConcern.class);
        this.preferredIngredients = codes(preferredIngredients, IngredientTag.class);
        this.avoidedIngredients = codes(avoidedIngredients, IngredientTag.class);
        this.interestCategories = codes(interestCategories, BeautyCategory.class);
    }

    public Set<SkinConcern> getConcerns() {
        return values(concerns, SkinConcern.class);
    }

    public Set<IngredientTag> getPreferredIngredients() {
        return values(preferredIngredients, IngredientTag.class);
    }

    public Set<IngredientTag> getAvoidedIngredients() {
        return values(avoidedIngredients, IngredientTag.class);
    }

    public Set<BeautyCategory> getInterestCategories() {
        return values(interestCategories, BeautyCategory.class);
    }

    /**
     * 임베딩할 글. 피하는 성분은 넣지 않는다(피하는 성분은 SQL 필터로만 거르고, 순서에는 쓰지 않는다).
     * 예: "피부 타입: 지성\n피부 고민: 트러블, 모공\n선호 성분: 나이아신아마이드\n관심 카테고리: 스킨케어"
     */
    public String embeddingSource() {
        return "피부 타입: " + skinType.getLabel()
                + "\n피부 고민: " + labels(getConcerns().stream().map(SkinConcern::getLabel))
                + "\n선호 성분: " + labels(getPreferredIngredients().stream().map(IngredientTag::getLabel))
                + "\n관심 카테고리: " + labels(getInterestCategories().stream().map(BeautyCategory::getLabel));
    }

    private static String labels(Stream<String> labels) {
        String joined = labels.collect(Collectors.joining(", "));
        return joined.isEmpty() ? "없음" : joined;
    }

    private static void checkSide(List<IngredientTag> tags, IngredientTag.Side side) {
        if (tags.stream().anyMatch(tag -> tag.getSide() != side)) throw invalid();
    }

    // 중복이면 거절하고, 저장은 코드표 순서로 한다(응답 순서가 늘 같게)
    private static <E extends Enum<E>> String[] codes(Collection<E> values, Class<E> type) {
        EnumSet<E> set = values.isEmpty() ? EnumSet.noneOf(type) : EnumSet.copyOf(values);
        if (set.size() != values.size()) throw invalid();
        return set.stream().map(Enum::name).toArray(String[]::new);
    }

    private static <E extends Enum<E>> Set<E> values(String[] codes, Class<E> type) {
        EnumSet<E> set = EnumSet.noneOf(type);
        Arrays.stream(codes).map(code -> Enum.valueOf(type, code)).forEach(set::add);
        return set;
    }

    private static DomainException invalid() {
        return new DomainException(RecommendErrorCode.SURVEY_ANSWER_INVALID);
    }
}
