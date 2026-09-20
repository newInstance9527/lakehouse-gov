package vip.xiaonuo.lh.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

@Configuration
@EnableScheduling
public class LhConfigure {

    @Bean
    public RestTemplate lhRestTemplate() {
        return new RestTemplate();
    }
}
