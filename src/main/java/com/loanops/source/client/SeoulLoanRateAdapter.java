package com.loanops.source.client;

import com.loanops.source.config.SeoulLoanRateProperties;
import com.loanops.source.config.SourceRestClientFactory;
import com.loanops.source.domain.DataSourceCode;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.util.StreamUtils;
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
import java.nio.charset.StandardCharsets;

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
        return fetchPage(1, properties.pageSize());
    }

    public RawSourceResponse fetchPage(int startIndex, int endIndex) {
        requireConfiguration();
        URI uri = UriComponentsBuilder.fromUriString(properties.baseUrl())
                .pathSegment(properties.apiKey(), "xml", properties.serviceName())
                .pathSegment(String.valueOf(startIndex), String.valueOf(endIndex))
                .build()
                .encode()
                .toUri();

        return restClient.get()
                .uri(uri)
                .exchange((request, response) -> new RawSourceResponse(
                        contentType(response.getHeaders(), MediaType.APPLICATION_XML_VALUE),
                        StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8),
                        response.getStatusCode().value()
                ));
    }

    @Override
    public SourceResponseValidation validate(RawSourceResponse response) {
        String code = resultCode(response.rawPayload());
        return "INFO-000".equals(code)
                ? SourceResponseValidation.success(code)
                : SourceResponseValidation.failure(code);
    }

    public int totalCount(RawSourceResponse response) {
        try {
            Document document = parse(response.rawPayload());
            NodeList counts = document.getElementsByTagName("list_total_count");
            if (counts.getLength() == 0) {
                throw new IllegalArgumentException("Seoul response is missing list_total_count");
            }
            int totalCount = Integer.parseInt(counts.item(0).getTextContent().strip());
            if (totalCount < 0) {
                throw new IllegalArgumentException("Seoul response contains an invalid list_total_count");
            }
            return totalCount;
        } catch (ParserConfigurationException | SAXException | IOException | NumberFormatException exception) {
            throw new IllegalArgumentException("Seoul response contains an invalid list_total_count");
        }
    }

    public int pageSize() {
        if (properties.pageSize() < 1) {
            throw new IllegalStateException("Seoul page size must be positive");
        }
        return properties.pageSize();
    }

    public int rowCount(RawSourceResponse response) {
        try {
            return parse(response.rawPayload()).getElementsByTagName("row").getLength();
        } catch (ParserConfigurationException | SAXException | IOException exception) {
            throw new IllegalArgumentException("Seoul response contains invalid XML");
        }
    }

    private String resultCode(String payload) {
        try {
            Document document = parse(payload);
            NodeList codes = document.getElementsByTagName("CODE");
            if (codes.getLength() == 0) {
                return null;
            }
            return codes.item(0).getTextContent().strip();
        } catch (ParserConfigurationException | SAXException | IOException exception) {
            return null;
        }
    }

    private Document parse(String payload) throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "");
        factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(payload)));
    }

    private void requireConfiguration() {
        if (!StringUtils.hasText(properties.baseUrl())
                || !StringUtils.hasText(properties.serviceName())
                || !StringUtils.hasText(properties.apiKey())
                || properties.pageSize() < 1) {
            throw new IllegalStateException("Required configuration is missing for SEOUL_POLICY_LOAN_RATE");
        }
    }

    private String contentType(HttpHeaders headers, String fallback) {
        MediaType contentType = headers.getContentType();
        return contentType == null ? fallback : contentType.toString();
    }
}
