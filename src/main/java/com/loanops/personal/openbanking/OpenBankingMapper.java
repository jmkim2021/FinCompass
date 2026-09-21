package com.loanops.personal.openbanking;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.*;
import java.util.*;
import static com.loanops.personal.openbanking.OpenBankingModels.*;
import static com.loanops.personal.openbanking.OpenBankingException.Category.*;

/** Validates provider observations only. Phase 2 owns canonical personal finance and DQ. */
public final class OpenBankingMapper {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("uuuuMMddHHmmssSSS").withResolverStyle(ResolverStyle.STRICT);
    private final OpenBankingJson json;
    OpenBankingMapper(OpenBankingJson json) { this.json = json; }
    private JsonNode successful(RawResponse response) {
        if (response.httpStatus() < 200 || response.httpStatus() >= 300) throw new OpenBankingException(HTTP_ERROR);
        JsonNode n = json.parse(response.body());
        if (!"A0000".equals(OpenBankingJson.required(n, "rsp_code"))) throw new OpenBankingException(UPSTREAM_ERROR);
        if (n.hasNonNull("bank_rsp_code") && !"000".equals(n.get("bank_rsp_code").asText())) throw new OpenBankingException(UPSTREAM_ERROR);
        return n;
    }
    public Balance balance(RawResponse response, OpenBankingMode mode) {
        JsonNode n = successful(response);
        try {
            Instant observed = n.hasNonNull("api_tran_dtm")
                    ? LocalDateTime.parse(OpenBankingJson.required(n, "api_tran_dtm"), STAMP).atZone(SEOUL).toInstant()
                    : response.receivedAt();
            if (observed.isAfter(response.receivedAt().plusSeconds(300))) throw new IllegalArgumentException();
            return new Balance(money(n, "balance_amt", true), optionalMoney(n, "available_amt"), observed,
                    mode == OpenBankingMode.LIVE_TEST ? "OPEN_BANKING_TEST" : "DEMO_REPLAY");
        } catch (IllegalArgumentException | DateTimeException ex) { throw new OpenBankingException(INVALID_RESPONSE); }
    }
    public TransactionPage transactions(RawResponse response, LocalDate from, LocalDate to) {
        JsonNode n = successful(response);
        try {
            String next = OpenBankingJson.required(n, "next_page_yn");
            if (!next.equals("Y") && !next.equals("N")) throw new IllegalArgumentException();
            int count = Integer.parseInt(OpenBankingJson.required(n, "page_record_cnt"));
            JsonNode rows = n.get("res_list");
            if (rows == null || !rows.isArray() || count != rows.size() || count < 0 || count > 25) throw new IllegalArgumentException();
            List<ObservedTransaction> result = new ArrayList<>();
            for (JsonNode row : rows) {
                LocalDate day = LocalDate.parse(OpenBankingJson.required(row, "tran_date"), DATE);
                if (day.isBefore(from) || day.isAfter(to)) throw new IllegalArgumentException();
                String direction = OpenBankingJson.required(row, "inout_type");
                if (!Set.of("입금", "출금").contains(direction)) throw new IllegalArgumentException();
                result.add(new ObservedTransaction(day, LocalTime.parse(OpenBankingJson.required(row, "tran_time"), TIME),
                        direction, OpenBankingJson.required(row, "tran_type"), money(row, "tran_amt", false),
                        money(row, "after_balance_amt", true), optionalText(row, "print_content"), optionalText(row, "branch_name")));
            }
            String cursor = "Y".equals(next) ? OpenBankingJson.required(n, "befor_inquiry_trace_info") : null;
            if (cursor != null && (cursor.length() > 512 || count == 0)) throw new IllegalArgumentException();
            return new TransactionPage(List.copyOf(result), "Y".equals(next), cursor);
        } catch (IllegalArgumentException | DateTimeException ex) { throw new OpenBankingException(INVALID_RESPONSE); }
    }
    private static String optionalText(JsonNode n, String key) {
        if (!n.hasNonNull(key)) return "";
        if (!n.get(key).isTextual() || n.get(key).asText().length() > 1000) throw new IllegalArgumentException();
        return n.get(key).asText();
    }
    private static BigDecimal money(JsonNode n, String key, boolean signed) {
        String value = OpenBankingJson.required(n, key);
        if (!value.matches(signed ? "-?[0-9]{1,18}(\\.[0-9]{1,2})?" : "[0-9]{1,18}(\\.[0-9]{1,2})?")) throw new IllegalArgumentException();
        return new BigDecimal(value).stripTrailingZeros();
    }
    private static BigDecimal optionalMoney(JsonNode n, String key) {
        return n.hasNonNull(key) && !n.get(key).asText().isBlank() ? money(n, key, true) : null;
    }
    public static boolean stale(Balance balance, Instant now, Duration maximumAge) {
        return balance.observedAt().isBefore(now.minus(maximumAge));
    }
}
