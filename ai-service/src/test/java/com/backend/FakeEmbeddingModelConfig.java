package com.backend;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 테스트는 실제 OpenAI를 부르지 않는다. 글마다 같은 값이 나오는 1536차원 가짜 벡터를 돌려준다.
 * failing을 켜면 예외를 던져 OpenAI 장애를 흉내 낸다.
 */
@Configuration
public class FakeEmbeddingModelConfig {

    public static final int DIMENSIONS = 1536;

    @Bean
    @Primary
    public FakeEmbeddingModel fakeEmbeddingModel() {
        return new FakeEmbeddingModel();
    }

    public static class FakeEmbeddingModel implements EmbeddingModel {
        public volatile boolean failing;
        public final AtomicInteger calls = new AtomicInteger();

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            calls.incrementAndGet();
            if (failing) throw new IllegalStateException("가짜 OpenAI 장애");
            List<Embedding> embeddings = new ArrayList<>();
            for (int i = 0; i < request.getInstructions().size(); i++) {
                embeddings.add(new Embedding(vector(request.getInstructions().get(i)), i));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return vector(document.getText());
        }

        static float[] vector(String text) {
            float[] v = new float[DIMENSIONS];
            v[Math.floorMod(text.hashCode(), DIMENSIONS)] = 1f;
            return v;
        }
    }
}
