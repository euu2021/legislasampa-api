package br.com.sampachat.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Serviço para envio de alertas
 */
@Service
public class AlertService {
    
    private static final Logger logger = LoggerFactory.getLogger(AlertService.class);
    
    @Value("${app.alert.email.enabled:false}")
    private boolean emailAlertsEnabled;
    
    @Value("${app.alert.email.to:}")
    private String alertEmailTo;
    
    /**
     * Envia um alerta de falha na sincronização
     * 
     * @param message Mensagem de erro
     */
    public void sendSyncFailureAlert(String message) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String alertMessage = "ALERTA: Falha na sincronização do LegislaSampa em " + timestamp + "\n\n" + message;
        
        // Loga o alerta (sempre)
        logger.error(alertMessage);
        
        // Aviso sobre email (se configurado)
        if (emailAlertsEnabled) {
            logger.info("Alertas por email estão configurados para {}, mas o envio de email não está implementado nesta versão", alertEmailTo);
        }
    }
}
