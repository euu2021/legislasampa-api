package br.com.sampachat.api.controller;

import br.com.sampachat.api.service.SyncStatusService;
import br.com.sampachat.api.service.SyncStatusService.SyncStatus;
import br.com.sampachat.api.service.SyncStatusService.SyncStatusEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Controlador para monitoramento da aplicação
 */
@RestController
@RequestMapping("/monitor")
public class MonitorController {

    @Autowired
    private SyncStatusService syncStatusService;

    /**
     * Endpoint para verificar o status atual da sincronização
     */
    @GetMapping("/sync/status")
    public ResponseEntity<SyncStatus> getSyncStatus() {
        return ResponseEntity.ok(syncStatusService.getCurrentStatus());
    }

    /**
     * Endpoint para verificar o histórico de sincronizações
     */
    @GetMapping("/sync/history")
    public ResponseEntity<List<SyncStatusEntry>> getSyncHistory() {
        return ResponseEntity.ok(syncStatusService.getSyncHistory());
    }

    /**
     * Endpoint para health check
     */
    @GetMapping("/health")
    public ResponseEntity<String> healthCheck() {
        SyncStatus status = syncStatusService.getCurrentStatus();
        
        if (status.isHealthy()) {
            return ResponseEntity.ok("OK - Sistema saudável");
        } else {
            return ResponseEntity
                    .status(500)
                    .body("ALERTA - " + status.getConsecutiveFailures() + 
                            " falhas consecutivas de sincronização. Última falha: " + 
                            (status.getLastErrorMessage() != null ? status.getLastErrorMessage() : "N/A"));
        }
    }
}
