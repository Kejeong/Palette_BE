package com.backend.boundedContext.recommend.out;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 임베딩(pgvector vector) 컬럼 읽기·쓰기. 엔티티는 이 컬럼을 매핑하지 않는다.
 * 벡터는 '[0.1,0.2,…]' 문자열을 cast(? as vector)로 넘긴다.
 */
@Repository
@RequiredArgsConstructor
public class RecommendVectorRepository {
    private final JdbcTemplate jdbcTemplate;

    /** 임베딩을 기다리는 판매 중 뷰티 상품 ID (뷰티가 아닌 상품은 추천하지 않으므로 임베딩하지 않는다) */
    public List<Long> findProductIdsToEmbed(int limit) {
        return jdbcTemplate.queryForList(
                "select id from recommend_product where embedding is null and on_sale and beauty order by id limit ?",
                Long.class, limit);
    }

    /** 임베딩을 저장한다. 그사이 상품 글이 바뀌었으면(해시가 다르면) 저장하지 않고 false */
    public boolean saveProductEmbedding(Long productId, String sourceHash, float[] embedding) {
        return jdbcTemplate.update(
                "update recommend_product set embedding = cast(? as vector) where id = ? and embedding_source_hash = ?",
                toVector(embedding), productId, sourceHash) == 1;
    }

    public void clearProductEmbedding(Long productId) {
        jdbcTemplate.update("update recommend_product set embedding = null where id = ?", productId);
    }

    /** 임베딩을 기다리는 설문의 회원 ID */
    public List<Long> findProfileMemberIdsToEmbed(int limit) {
        return jdbcTemplate.queryForList(
                "select member_id from beauty_profile where embedding is null order by id limit ?", Long.class, limit);
    }

    /** 설문 임베딩을 저장한다. 그사이 설문을 다시 저장했으면(updated_at이 다르면) 저장하지 않고 false */
    public boolean saveProfileEmbedding(Long memberId, LocalDateTime updatedAt, float[] embedding) {
        return jdbcTemplate.update(
                "update beauty_profile set embedding = cast(? as vector) where member_id = ? and updated_at = ?",
                toVector(embedding), memberId, updatedAt) == 1;
    }

    public void clearProfileEmbedding(Long memberId) {
        jdbcTemplate.update("update beauty_profile set embedding = null where member_id = ?", memberId);
    }

    static String toVector(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(embedding[i]);
        }
        return sb.append(']').toString();
    }
}
