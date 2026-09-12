package com.loanops.source.client;

import com.loanops.source.config.SeoulLoanRateProperties;
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
public class SeoulLoanRateAdapter implements SourceAdapter {

    private final SeoulLoanRateProperties properties;
    private final RestClient restClient;

    public SeoulLoanRateAdapter(SeoulLoanRateProperties properties, SourceRestClientFactory restClientFactory) {
        this.properties = properties;
        this.restClient = restClientFactory.create(properties.connectTimeout(), properties.readTimeout());
    }

    @Override
    public DataSourceCode sourceCode() {
        return DataSourceCode.SEOUL_POLICY_LOAN_RATE;
    }

    @Override
    public RawSourceResponse fetch() {
        requireConfiguration();
        URI uri = UriComponentsBuilder.fromUriString(properties.baseUrl())
                .pathSegment(properties.apiKey(), "xml", properties.serviceName())
                .pathSegment(String.valueOf(properties.startIndex()), String.valueOf(properties.endIndex()))
                .build()
                .encode()
                .toUri();

        ResponseEntity<String> response = restClient.get()
                .uri(uri)
                .retrieve()
                .toEntity(String.class);

        return new RawSourceResponse(
                contentType(response, MediaType.APPLICATION_XML_VALUE),
                response.getBody() == null ? "" : response.getBody(),
                response.getStatusCode().value()
        );
    }

    private void requireConfiguration() {
        if (!StringUtils.hasText(properties.baseUrl())
                || !StringUtils.hasText(properties.serviceName())
                || !StringUtils.hasText(properties.apiKey())) {
            throw new IllegalStateException("Required configuration is missing for SEOUL_POLICY_LOAN_RATE");
        }
    }

    private String contentType(ResponseEntity<String> response, String fallback) {
        MediaType contentType = response.getHeaders().getContentType();
        return contentType == null ? fallback : contentType.toString();
    }
}
