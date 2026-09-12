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
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
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

    @Override
    public SourceResponseValidation validate(RawSourceResponse response) {
        String code = resultCode(response.rawPayload());
        return "INFO-000".equals(code)
                ? SourceResponseValidation.success(code)
                : SourceResponseValidation.failure(code);
    }

    private String resultCode(String payload) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            Document document = factory.newDocumentBuilder()
                    .parse(new InputSource(new StringReader(payload)));
            NodeList codes = document.getElementsByTagName("CODE");
            if (codes.getLength() == 0) {
                return null;
            }
            return codes.item(0).getTextContent().strip();
        } catch (ParserConfigurationException | SAXException | IOException exception) {
            return null;
        }
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
