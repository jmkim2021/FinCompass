package com.loanops.source.client;

import com.loanops.source.domain.DataSourceCode;

public interface SourceAdapter {

    DataSourceCode sourceCode();

    RawSourceResponse fetch();
}
