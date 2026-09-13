package com.loanops.normalization.parser;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanops.normalization.NormalizationException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

@Component
public class FssFinlifeParser {

    private static final DateTimeFormatter SOURCE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final ObjectMapper objectMapper;

    public FssFinlifeParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParsedResponse parse(String rawPayload) {
        try {
            FssResponse response = objectMapper.readValue(rawPayload, FssResponse.class);
            if (response.result() == null || !"000".equals(response.result().errorCode())) {
                throw new NormalizationException("FSS snapshot does not contain a successful result envelope");
            }
            List<BaseProduct> products = response.result().baseProducts() == null
                    ? List.of()
                    : response.result().baseProducts().stream().map(this::toBaseProduct).toList();
            List<ProductOption> options = response.result().productOptions() == null
                    ? List.of()
                    : response.result().productOptions().stream().map(this::toProductOption).toList();
            return new ParsedResponse(products, options);
        } catch (JsonProcessingException exception) {
            throw new NormalizationException("FSS snapshot contains invalid JSON", exception);
        }
    }

    private BaseProduct toBaseProduct(BaseProductPayload payload) {
        return new BaseProduct(
                required(payload.institutionCode(), "fin_co_no"),
                required(payload.institutionName(), "kor_co_nm"),
                required(payload.productId(), "fin_prdt_cd"),
                required(payload.productName(), "fin_prdt_nm"),
                required(payload.disclosureMonth(), "dcls_month"),
                optionalDate(payload.disclosureStartDate(), "dcls_strt_day"),
                optionalDate(payload.disclosureEndDate(), "dcls_end_day"),
                optionalTimestamp(payload.sourceSubmittedAt(), "fin_co_subm_day")
        );
    }

    private ProductOption toProductOption(ProductOptionPayload payload) {
        return new ProductOption(
                required(payload.institutionCode(), "fin_co_no"),
                required(payload.productId(), "fin_prdt_cd"),
                required(payload.disclosureMonth(), "dcls_month"),
                required(payload.mortgageTypeCode(), "mrtg_type"),
                required(payload.mortgageTypeName(), "mrtg_type_nm"),
                required(payload.repaymentTypeCode(), "rpay_type"),
                required(payload.repaymentTypeName(), "rpay_type_nm"),
                required(payload.lendingRateTypeCode(), "lend_rate_type"),
                required(payload.lendingRateTypeName(), "lend_rate_type_nm"),
                requiredDecimal(payload.minimumRate(), "lend_rate_min"),
                requiredDecimal(payload.maximumRate(), "lend_rate_max"),
                optionalDecimal(payload.averageRate(), "lend_rate_avg")
        );
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new NormalizationException("FSS record is missing required field " + field);
        }
        return value;
    }

    private LocalDate optionalDate(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE);
        } catch (DateTimeParseException exception) {
            throw new NormalizationException("FSS record contains invalid field " + field, exception);
        }
    }

    private LocalDateTime optionalTimestamp(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, SOURCE_TIMESTAMP);
        } catch (DateTimeParseException exception) {
            throw new NormalizationException("FSS record contains invalid field " + field, exception);
        }
    }

    private BigDecimal requiredDecimal(String value, String field) {
        BigDecimal decimal = optionalDecimal(value, field);
        if (decimal == null) {
            throw new NormalizationException("FSS record is missing required field " + field);
        }
        return decimal;
    }

    private BigDecimal optionalDecimal(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            throw new NormalizationException("FSS record contains invalid field " + field, exception);
        }
    }

    public record ParsedResponse(List<BaseProduct> products, List<ProductOption> options) {
    }

    public record BaseProduct(
            String institutionCode,
            String institutionName,
            String productId,
            String productName,
            String disclosureMonth,
            LocalDate disclosureStartDate,
            LocalDate disclosureEndDate,
            LocalDateTime sourceSubmittedAt
    ) {
    }

    public record ProductOption(
            String institutionCode,
            String productId,
            String disclosureMonth,
            String mortgageTypeCode,
            String mortgageTypeName,
            String repaymentTypeCode,
            String repaymentTypeName,
            String lendingRateTypeCode,
            String lendingRateTypeName,
            BigDecimal minimumRate,
            BigDecimal maximumRate,
            BigDecimal averageRate
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FssResponse(ResultPayload result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ResultPayload(
            @JsonProperty("err_cd") String errorCode,
            @JsonProperty("baseList") List<BaseProductPayload> baseProducts,
            @JsonProperty("optionList") List<ProductOptionPayload> productOptions
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BaseProductPayload(
            @JsonProperty("fin_co_no") String institutionCode,
            @JsonProperty("kor_co_nm") String institutionName,
            @JsonProperty("fin_prdt_cd") String productId,
            @JsonProperty("fin_prdt_nm") String productName,
            @JsonProperty("dcls_month") String disclosureMonth,
            @JsonProperty("dcls_strt_day") String disclosureStartDate,
            @JsonProperty("dcls_end_day") String disclosureEndDate,
            @JsonProperty("fin_co_subm_day") String sourceSubmittedAt
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProductOptionPayload(
            @JsonProperty("fin_co_no") String institutionCode,
            @JsonProperty("fin_prdt_cd") String productId,
            @JsonProperty("dcls_month") String disclosureMonth,
            @JsonProperty("mrtg_type") String mortgageTypeCode,
            @JsonProperty("mrtg_type_nm") String mortgageTypeName,
            @JsonProperty("rpay_type") String repaymentTypeCode,
            @JsonProperty("rpay_type_nm") String repaymentTypeName,
            @JsonProperty("lend_rate_type") String lendingRateTypeCode,
            @JsonProperty("lend_rate_type_nm") String lendingRateTypeName,
            @JsonProperty("lend_rate_min") String minimumRate,
            @JsonProperty("lend_rate_max") String maximumRate,
            @JsonProperty("lend_rate_avg") String averageRate
    ) {
    }
}
