package com.loanops.source.client;

import com.loanops.source.config.FssFinlifeProperties;
import com.loanops.source.config.SourceRestClientFactory;
import com.loanops.source.domain.DataSourceCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@Component
public class FssFinlifeAdapter implements SourceAdapter {

    private final FssFinlifeProperties properties;
    private final RestClient restClient;

    public FssFinlifeAdapter(FssFinlifeProperties properties, SourceRestClientFactory restClientFactory) {
        this.properties = properties;
        this.restClient = restClientFactory.create(properties.connectTimeout(), properties.readTimeout());
    }

    @Override
    public DataSourceCode sourceCode() {
        return DataSourceCode.FSS_FINLIFE;
    }

    @Override
    public RawSourceResponse fetch() {
        requireConfiguration();
        URI uri = UriComponentsBuilder.fromUriString(properties.baseUrl())
                .path(properties.path())
                .queryParam("auth", properties.apiKey())
                .queryParam("topFinGrpNo", properties.topFinGroupNo())
                .queryParam("pageNo", properties.pageNo())
                .build()
                .encode()
                .toUri();

        ResponseEntity<String> response = restClient.get()
                .uri(uri)
                .retrieve()
                .toEntity(String.class);

        return new RawSourceResponse(
                contentType(response, MediaType.APPLICATION_JSON_VALUE),
                response.getBody() == null ? "" : response.getBody(),
                response.getStatusCode().value()
        );
    }

    private void requireConfiguration() {
        if (!StringUtils.hasText(properties.baseUrl())
                || !StringUtils.hasText(properties.path())
                || !StringUtils.hasText(properties.apiKey())) {
            throw new IllegalStateException("Required configuration is missing for FSS_FINLIFE");
        }
    }

    private String contentType(ResponseEntity<String> response, String fallback) {
        MediaType contentType = response.getHeaders().getContentType();
        return contentType == null ? fallback : contentType.toString();
    }
}
