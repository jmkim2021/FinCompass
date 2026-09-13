package com.loanops.normalization.parser;

import com.loanops.normalization.NormalizationException;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Component
public class SeoulLoanRateParser {

    public ParsedPage parse(String rawPayload) {
        try {
            Document document = parseDocument(rawPayload);
            Element root = document.getDocumentElement();
            if (!"searchLoOrgIrList".equals(root.getTagName())) {
                throw new NormalizationException("Seoul snapshot contains an unexpected root element");
            }
            String resultCode = requiredText(root, "CODE");
            if (!"INFO-000".equals(resultCode)) {
                throw new NormalizationException("Seoul snapshot does not contain a successful result code");
            }
            int totalCount = Integer.parseInt(requiredText(root, "list_total_count"));
            NodeList rowNodes = root.getElementsByTagName("row");
            List<RateRow> rows = new ArrayList<>(rowNodes.getLength());
            for (int index = 0; index < rowNodes.getLength(); index++) {
                rows.add(toRateRow((Element) rowNodes.item(index)));
            }
            return new ParsedPage(totalCount, List.copyOf(rows));
        } catch (NormalizationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new NormalizationException("Seoul snapshot contains invalid XML data", exception);
        }
    }

    private Document parseDocument(String payload) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(payload)));
    }

    private RateRow toRateRow(Element row) {
        return new RateRow(
                requiredText(row, "lo_org_cd"),
                requiredText(row, "lo_org_nm"),
                LocalDate.parse(requiredText(row, "base_str_dt"), DateTimeFormatter.BASIC_ISO_DATE),
                LocalDate.parse(requiredText(row, "base_end_dt"), DateTimeFormatter.BASIC_ISO_DATE),
                Long.parseLong(requiredText(row, "lo_cnt")),
                new BigDecimal(requiredText(row, "min_lo_ir")),
                new BigDecimal(requiredText(row, "max_lo_ir")),
                new BigDecimal(requiredText(row, "avg_lo_ir")),
                new BigDecimal(requiredText(row, "min_lo_ale_ir")),
                new BigDecimal(requiredText(row, "max_lo_ale_ir")),
                new BigDecimal(requiredText(row, "avg_lo_ale_ir"))
        );
    }

    private String requiredText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            throw new NormalizationException("Seoul record is missing required field " + tagName);
        }
        String value = nodes.item(0).getTextContent().strip();
        if (value.isEmpty()) {
            throw new NormalizationException("Seoul record is missing required field " + tagName);
        }
        return value;
    }

    public record ParsedPage(int totalCount, List<RateRow> rows) {
    }

    public record RateRow(
            String institutionCode,
            String institutionName,
            LocalDate referenceStartDate,
            LocalDate referenceEndDate,
            long loanCount,
            BigDecimal minimumLoanRate,
            BigDecimal maximumLoanRate,
            BigDecimal averageLoanRate,
            BigDecimal minimumLoanAleRate,
            BigDecimal maximumLoanAleRate,
            BigDecimal averageLoanAleRate
    ) {
    }
}
