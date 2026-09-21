package com.loanops.personal.canonical;

import com.fasterxml.jackson.databind.*;
import com.loanops.personal.openbanking.SecretBox;
import static com.loanops.personal.canonical.PersonalFinanceException.Code.*;

public final class PersonalFinanceCipher {
    private final SecretBox box;
    private final ObjectMapper json;
    public PersonalFinanceCipher(SecretBox box,ObjectMapper json) {
        this.box=box;this.json=json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
    public String seal(String context,Object value) {
        try { return box.seal("personal:"+context,json.writeValueAsString(value)); }
        catch(Exception ex) { throw new PersonalFinanceException(STORAGE_ERROR); }
    }
    public <T> T open(String context,String value,Class<T> type) {
        try { return json.readValue(box.open("personal:"+context,value),type); }
        catch(Exception ex) { throw new PersonalFinanceException(STORAGE_ERROR); }
    }
    public String contentHash(Object value) {
        try { return box.identity("personal-content-v1:"+json.writeValueAsString(value)); }
        catch(Exception ex) { throw new PersonalFinanceException(STORAGE_ERROR); }
    }
}
