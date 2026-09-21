package com.loanops.fincompass.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;

/** Rejects drafts containing a numeric token that is absent from user input or tool results. */
public final class NumericGrounding {
    private static final Pattern NUMBER=Pattern.compile("(?<![A-Za-z0-9])[-+]?\\d[\\d,]*(?:\\.\\d+)?");
    public Set<String> evidence(String question,JsonNode request,List<JsonNode> results){
        Set<String> values=new LinkedHashSet<>();collectText(question,values);collect(request,values);results.forEach(v->collect(v,values));return values;
    }
    public boolean supported(String answer,Set<String> allowed){
        Matcher matcher=NUMBER.matcher(answer==null?"":answer);
        while(matcher.find())if(!allowed.contains(normalize(matcher.group())))return false;
        return true;
    }
    private void collect(JsonNode node,Set<String> values){
        if(node==null)return;
        if(node.isNumber())add(node.decimalValue(),values);
        else if(node.isTextual())collectText(node.textValue(),values);
        else if(node.isContainerNode())node.elements().forEachRemaining(value->collect(value,values));
    }
    private void collectText(String value,Set<String> values){
        Matcher matcher=NUMBER.matcher(value==null?"":value);while(matcher.find())values.add(normalize(matcher.group()));
    }
    private void add(BigDecimal value,Set<String> values){
        values.add(normalize(value.toPlainString()));
        if(value.abs().compareTo(BigDecimal.ONE)<0)values.add(normalize(value.multiply(new BigDecimal("100")).toPlainString()));
    }
    private String normalize(String value){
        try{return new BigDecimal(value.replace(",","")).stripTrailingZeros().toPlainString();}
        catch(NumberFormatException ignored){return value.replace(",","");}
    }
}
