package com.backend.boundedContext.recommend.app;

import com.backend.boundedContext.recommend.domain.BeautyProfile;
import com.backend.boundedContext.recommend.domain.RecommendPolicy;
import com.backend.boundedContext.recommend.domain.RecommendProduct;
import com.backend.boundedContext.recommend.out.BeautyProfileRepository;
import com.backend.boundedContext.recommend.out.RecommendProductRepository;
import com.backend.boundedContext.recommend.out.RecommendVectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 비어 있는 임베딩(상품·설문)을 OpenAI로 채운다. 트랜잭션 밖에서 부른다(외부 호출 동안 DB 연결을 잡지 않게).
 * 임베딩하는 동안 글이 바뀌었으면 저장하지 않고 다음 차례에 다시 만든다.
 * 실패하면 WARN만 남기고 그대로 둔다. 다음 차례에 다시 시도한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendEmbedUseCase {
    private final EmbeddingModel embeddingModel;
    private final RecommendPolicy recommendPolicy;
    private final RecommendProductRepository recommendProductRepository;
    private final BeautyProfileRepository beautyProfileRepository;
    private final RecommendVectorRepository recommendVectorRepository;

    public void embedPending() {
        embedProducts();
        embedProfiles();
    }

    private void embedProducts() {
        List<Long> ids = recommendVectorRepository.findProductIdsToEmbed(recommendPolicy.getEmbeddingBatchSize());
        if (ids.isEmpty()) return;

        List<RecommendProduct> products = recommendProductRepository.findAllById(ids);
        List<float[]> embeddings = embed("PRODUCT", products.stream().map(RecommendProduct::embeddingSource).toList());
        if (embeddings == null) return;

        int saved = 0;
        for (int i = 0; i < products.size(); i++) {
            RecommendProduct product = products.get(i);
            if (recommendVectorRepository.saveProductEmbedding(product.getId(), product.getEmbeddingSourceHash(), embeddings.get(i))) saved++;
        }
        log.info("recommend embedding done target=PRODUCT count={} saved={}", products.size(), saved);
    }

    private void embedProfiles() {
        List<Long> memberIds = recommendVectorRepository.findProfileMemberIdsToEmbed(recommendPolicy.getEmbeddingBatchSize());
        if (memberIds.isEmpty()) return;

        List<BeautyProfile> profiles = memberIds.stream()
                .map(beautyProfileRepository::findByMemberId).flatMap(Optional::stream).toList();
        List<float[]> embeddings = embed("PROFILE", profiles.stream().map(BeautyProfile::embeddingSource).toList());
        if (embeddings == null) return;

        int saved = 0;
        for (int i = 0; i < profiles.size(); i++) {
            BeautyProfile profile = profiles.get(i);
            if (recommendVectorRepository.saveProfileEmbedding(profile.getMemberId(), profile.getUpdatedAt(), embeddings.get(i))) saved++;
        }
        log.info("recommend embedding done target=PROFILE count={} saved={}", profiles.size(), saved);
    }

    // 글 여러 개를 한 번의 호출로 임베딩한다. 실패하면 null
    private List<float[]> embed(String target, List<String> texts) {
        if (texts.isEmpty()) return null;
        try {
            return embeddingModel.embed(texts);
        } catch (RuntimeException e) {
            log.warn("recommend embedding failed target={} count={} reason={}", target, texts.size(), e.toString());
            return null;
        }
    }
}
