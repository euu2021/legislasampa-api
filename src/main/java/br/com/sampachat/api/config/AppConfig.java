package br.com.sampachat.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Configuração de beans para a aplicação
 */
@Configuration
public class AppConfig {

    /**
     * Configura um RestTemplate para chamadas HTTP
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
