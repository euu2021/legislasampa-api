package br.com.sampachat.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {
    // É possível personalizar o executor aqui se necessário
    // Por exemplo, definir tamanho de pool de threads, etc.
}
