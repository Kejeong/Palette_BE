package com.backend.boundedContext.faq.out;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * FAQ 임베딩(pgvector vector) 컬럼 읽기·쓰기. 엔티티 Faq에는 이 컬럼의 필드가 없음.
 * 벡터는 '[0.1,0.2,…]' 문자열을 cast(? as vector)로 넘김.
 */
@Repository
@RequiredArgsConstructor
public class FaqVectorRepository {

    private final JdbcTemplate jdbcTemplate;

    // 임베딩을 기다리는 FAQ ID. 숨긴 FAQ도 채움(다시 노출할 때 바로 검색되게)
    public List<Long> findIdsToEmbed(int limit) {
        return jdbcTemplate.queryForList("select id from faq where embedding is null order by id limit ?", Long.class, limit);
    }

    // 임베딩을 저장함. 그사이 질문·답변이 바뀌었으면(해시가 다르면) 저장하지 않고 false
    public boolean saveEmbedding(Long faqId, String sourceHash, float[] embedding) {
        return jdbcTemplate.update("update faq set embedding = cast(? as vector) where id = ? and embedding_source_hash = ?", toVector(embedding), faqId, sourceHash) == 1;
    }

    // 검색 결과 한 줄. category는 코드 이름
    public record SearchRow(
            Long faqId,
            String category,
            String question,
            String answer,
            double similarity
    ) {}

    /**
     * 질문 벡터와 코사인 유사도가 기준값 이상인 노출 중 FAQ를 유사도 높은 순으로 limit건.
     * 인덱스 없이 전체를 비교함(V5 참고). 임베딩 전 FAQ는 빠짐
     */
    public List<SearchRow> search(float[] queryEmbedding, double threshold, int limit) {
        return jdbcTemplate.query("""
                        with q as (select cast(? as vector) as v)
                        select f.id, f.category, f.question, f.answer, 1 - (f.embedding <=> q.v) as similarity
                        from faq f, q
                        where f.visible and f.embedding is not null and 1 - (f.embedding <=> q.v) >= ?
                        order by f.embedding <=> q.v, f.id
                        limit ?
                        """,
                (rs, i) -> new SearchRow(rs.getLong("id"), rs.getString("category"), rs.getString("question"), rs.getString("answer"), rs.getDouble("similarity")),
                toVector(queryEmbedding), threshold, limit);
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
