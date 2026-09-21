package com.loanops.fincompass.demo;

import com.loanops.normalization.SourceNormalizationService;
import com.loanops.publication.PublicationService;
import com.loanops.source.client.RawSourceResponse;
import com.loanops.source.domain.DataSourceCode;
import com.loanops.source.service.SourceRunLifecycleService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;

/** Explicit demo-only bridge that still exercises the trusted LoanOps pipeline. */
@Configuration(proxyBeanMethods=false)
@Profile("golden-products")
@ConditionalOnProperty(name="fincompass.demo.enabled",havingValue="true")
public class GoldenProductConfiguration {
 @Bean @Order(20) ApplicationRunner goldenProductBootstrap(JdbcTemplate jdbc,SourceRunLifecycleService lifecycle,SourceNormalizationService normalization,PublicationService publication){return args->{
  if(jdbc.queryForObject("SELECT count(*) FROM loanops.serving_product_terms",Integer.class)>0)return;
  String body=new ClassPathResource("fixtures/products/golden-loans-v1.json").getContentAsString(StandardCharsets.UTF_8);
  long run=lifecycle.start(DataSourceCode.FSS_FINLIFE);lifecycle.preserveRawSnapshot(run,new RawSourceResponse("application/json",body,200));lifecycle.completeSuccess(run,200);normalization.normalize(run);publication.publish(run);
 };}
}
