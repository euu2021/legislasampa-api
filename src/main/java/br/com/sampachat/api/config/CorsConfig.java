package br.com.sampachat.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Configuração para permitir requisições CORS
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();
        
        // Permitir credenciais (para cabeçalhos personalizados como X-API-Key)
        config.setAllowCredentials(true);
        
        // Permitir origens específicas (incluindo cron-job.org)
        config.addAllowedOrigin("https://cron-job.org");
        config.addAllowedOrigin("https://api.cron-job.org");
        
        // Permitir qualquer cabeçalho
        config.addAllowedHeader("*");
        
        // Permitir métodos específicos
        config.addAllowedMethod("GET");
        config.addAllowedMethod("POST");
        config.addAllowedMethod("OPTIONS");
        
        // Aplicar a configuração para todos os endpoints da API
        source.registerCorsConfiguration("/api/**", config);
        
        return new CorsFilter(source);
    }
}
