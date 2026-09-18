package vip.xiaonuo.lh.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class LhConfigure {

    @Bean
    public RestTemplate lhRestTemplate() {
        return new RestTemplate();
    }
}
