package br.com.sampachat.api.controller;

import br.com.sampachat.api.service.DataIntegrityService;
import br.com.sampachat.api.service.SyncProjetosService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Controlador para ações administrativas, incluindo sincronização manual
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    
    private static final Logger logger = LoggerFactory.getLogger(AdminController.class);
    
    @Autowired
    private SyncProjetosService syncProjetosService;
    
    @Autowired
    private DataIntegrityService dataIntegrityService;
    
    @Value("${app.admin.secret-key:defaultKey}")
    private String secretKey;
    
    /**
     * Endpoint para iniciar sincronização manual via cron-job.org ou outra fonte externa.
     * Protegido por API key para evitar acesso não autorizado.
     * Responde imediatamente enquanto processa em segundo plano.
     * 
     * @param apiKey Chave de API fornecida no cabeçalho
     * @return Confirmação de que a sincronização foi iniciada
     */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> triggerSync(
            @RequestHeader(value = "X-API-Key", required = true) String apiKey) {
        
        // Verificar a chave de API para segurança
        if (!secretKey.equals(apiKey)) {
            logger.warn("Tentativa de sincronização com chave de API inválida");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "Chave de API inválida"));
        }
        
        logger.info("Sincronização manual iniciada via endpoint REST em {}", LocalDateTime.now());
        
        try {
            // Inicia o processo de sincronização em segundo plano e retorna imediatamente
            syncProjetosService.startAsyncSync();
            
            // Retorna sucesso imediatamente, sem esperar o resultado
            return ResponseEntity.ok(Map.of(
                    "success", true, 
                    "message", "Sincronização iniciada com sucesso em segundo plano",
                    "timestamp", LocalDateTime.now().toString()
            ));
        } catch (Exception e) {
            logger.error("Erro ao iniciar sincronização: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "success", false, 
                            "message", "Erro ao iniciar sincronização: " + e.getMessage(),
                            "timestamp", LocalDateTime.now().toString()
                    ));
        }
    }
    
    // O endpoint de health já existe em /health (Spring Actuator) e /monitor/health (MonitorController)
    // Portanto, não é necessário duplicar essa funcionalidade aqui
    
    /**
     * Endpoint para iniciar verificação de integridade manual via cron-job.org ou outra fonte externa.
     * Protegido por API key para evitar acesso não autorizado.
     * 
     * @param apiKey Chave de API fornecida no cabeçalho
     * @return Resultado da verificação de integridade
     */
    @PostMapping("/integrity")
    public ResponseEntity<Map<String, Object>> triggerIntegrityCheck(
            @RequestHeader(value = "X-API-Key", required = true) String apiKey) {
        
        // Verificar a chave de API para segurança
        if (!secretKey.equals(apiKey)) {
            logger.warn("Tentativa de verificação de integridade com chave de API inválida");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "Chave de API inválida"));
        }
        
        logger.info("Verificação de integridade manual iniciada via endpoint REST em {}", LocalDateTime.now());
        
        try {
            // Executar a verificação de integridade
            dataIntegrityService.scheduledIntegrityCheck();
            
            logger.info("Verificação de integridade manual concluída com sucesso");
            return ResponseEntity.ok(Map.of(
                    "success", true, 
                    "message", "Verificação de integridade concluída com sucesso",
                    "timestamp", LocalDateTime.now().toString()
            ));
            
        } catch (Exception e) {
            logger.error("Erro durante verificação de integridade manual: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "success", false, 
                            "message", "Erro durante verificação de integridade: " + e.getMessage(),
                            "timestamp", LocalDateTime.now().toString()
                    ));
        }
    }
}
