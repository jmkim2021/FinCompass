package com.loanops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LoanOpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoanOpsApplication.class, args);
    }
}
