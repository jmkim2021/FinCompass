package com.loanops.fincompass;
import com.loanops.fincompass.twin.GoldenDemoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
class GoldenDemoBoundaryTest {
 @Test void demoRejectsUnsetAndExternallyBoundAddresses(){for(String address:new String[]{"","0.0.0.0","192.168.1.10"})assertThatThrownBy(()->new GoldenDemoConfiguration(new MockEnvironment().withProperty("server.address",address))).isInstanceOf(IllegalArgumentException.class);}
 @Test void demoAcceptsOnlyExplicitLoopback(){for(String address:new String[]{"127.0.0.1","::1"})assertThatCode(()->new GoldenDemoConfiguration(new MockEnvironment().withProperty("server.address",address))).doesNotThrowAnyException();}
}
