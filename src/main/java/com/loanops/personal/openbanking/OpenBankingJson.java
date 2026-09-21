package com.loanops.personal.openbanking;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static com.loanops.personal.openbanking.OpenBankingException.Category.INVALID_RESPONSE;

final class OpenBankingJson {
    private final ObjectMapper mapper;
    OpenBankingJson(ObjectMapper mapper) { this.mapper = mapper.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS); }
    JsonNode parse(String value) {
        try { JsonNode n = mapper.readTree(value); if (n == null || !n.isObject()) throw new IllegalArgumentException(); return n; }
        catch (Exception ex) { throw new OpenBankingException(INVALID_RESPONSE); }
    }
    String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception ex) { throw new OpenBankingException(INVALID_RESPONSE); }
    }
    static String required(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || !n.isValueNode() || n.isNull() || n.asText().isBlank()) throw new OpenBankingException(INVALID_RESPONSE);
        return n.asText();
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    record Sanitized(String body, boolean redacted) { }
    Sanitized sanitize(String body, Collection<String> secrets) {
        try {
            JsonNode tree = parse(body).deepCopy();
            boolean changed = redact(tree, secrets);
            String result = changed ? write(tree) : body;
            return new Sanitized(result, changed);
        } catch (OpenBankingException ex) {
            return new Sanitized("{\"unparseable_body_omitted\":true}", true);
        }
    }
    private String scrub(String value, Collection<String> secrets) {
        for (String secret : secrets) if (secret != null && !secret.isBlank()) value = value.replace(secret, "[REDACTED]");
        return value;
    }
    private boolean redact(JsonNode node, Collection<String> secrets) {
        boolean changed = false;
        if (node.isObject()) {
            for (var field : new ArrayList<>(node.properties())) {
                if (!field.getKey().equals(scrub(field.getKey(), secrets))) {
                    ((ObjectNode)node).remove(field.getKey()); changed = true; continue;
                }
                String name = field.getKey().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                if (name.contains("token") || name.contains("secret") || name.contains("authorization")
                        || name.contains("header") || name.contains("password") || name.contains("apikey")
                        || name.equals("code") || name.equals("state")) {
                    ((ObjectNode)node).put(field.getKey(), "[REDACTED]"); changed = true;
                } else if (field.getValue().isTextual()) {
                    String original = field.getValue().asText();
                    String cleaned = scrub(original, secrets);
                    if (!original.equals(cleaned)) { ((ObjectNode)node).put(field.getKey(), cleaned); changed = true; }
                } else changed |= redact(field.getValue(), secrets);
            }
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode)node;
            for (int i = 0; i < array.size(); i++) {
                JsonNode child = array.get(i);
                if (child.isTextual()) {
                    String cleaned = scrub(child.asText(), secrets);
                    if (!child.asText().equals(cleaned)) { array.set(i, TextNode.valueOf(cleaned)); changed = true; }
                } else changed |= redact(child, secrets);
            }
        }
        return changed;
    }
}
