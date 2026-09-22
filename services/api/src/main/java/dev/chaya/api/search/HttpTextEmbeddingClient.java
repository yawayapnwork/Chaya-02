package dev.chaya.api.search;

import java.util.List;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Calls services/vision's POST /v1/embed-text. */
@Component
public class HttpTextEmbeddingClient implements TextEmbeddingClient {

    public record EmbedRequest(String text) {}

    public record EmbedResponse(List<Double> embedding, String model) {}

    private final RestClient client;

    public HttpTextEmbeddingClient(RestClient.Builder builder, SearchProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeoutMs = (int) props.visionTimeout().toMillis();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.client = builder.baseUrl(props.visionServiceUrl()).requestFactory(factory).build();
    }

    @Override
    public float[] embed(String text) {
        EmbedResponse response;
        try {
            response = client.post().uri("/v1/embed-text").body(new EmbedRequest(text)).retrieve().body(EmbedResponse.class);
        } catch (RestClientException e) {
            throw new EmbeddingUnavailableException("the vision service (embedding model) is unavailable: " + e.getMessage(), e);
        }
        if (response == null || response.embedding() == null || response.embedding().isEmpty()) {
            throw new EmbeddingUnavailableException("the vision service returned no embedding");
        }
        List<Double> values = response.embedding();
        float[] out = new float[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i).floatValue();
        }
        return out;
    }
}
