package br.com.sampachat.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Serviço para monitorar o status das sincronizações
 */
@Service
public class SyncStatusService {
    
    private static final Logger logger = LoggerFactory.getLogger(SyncStatusService.class);
    private static final int MAX_STATUS_ENTRIES = 50; // Limite de entradas no histórico
    
    // Uso de ConcurrentLinkedQueue para thread-safety
    private final ConcurrentLinkedQueue<SyncStatusEntry> syncHistory = new ConcurrentLinkedQueue<>();
    
    private LocalDateTime lastSuccessfulSync;
    private LocalDateTime lastFailedSync;
    private String lastErrorMessage;
    private int consecutiveFailures = 0;
    private int totalSyncs = 0;
    private int successfulSyncs = 0;
    
    /**
     * Registra uma sincronização bem-sucedida
     */
    public synchronized void registerSyncSuccess() {
        lastSuccessfulSync = LocalDateTime.now();
        consecutiveFailures = 0;
        totalSyncs++;
        successfulSyncs++;
        
        SyncStatusEntry entry = new SyncStatusEntry(
                lastSuccessfulSync,
                true,
                null,
                0 // Nenhuma tentativa de retry em caso de sucesso
        );
        
        addToHistory(entry);
        
        logger.info("Sincronização bem-sucedida registrada em {}", 
                lastSuccessfulSync.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    }
    
    /**
     * Registra uma falha na sincronização
     * 
     * @param errorMessage Mensagem de erro
     */
    public synchronized void registerSyncFailure(String errorMessage) {
        lastFailedSync = LocalDateTime.now();
        lastErrorMessage = errorMessage;
        consecutiveFailures++;
        totalSyncs++;
        
        SyncStatusEntry entry = new SyncStatusEntry(
                lastFailedSync,
                false,
                errorMessage,
                0 // Será atualizado se houver retry
        );
        
        addToHistory(entry);
        
        logger.error("Falha de sincronização registrada em {}: {}. Falhas consecutivas: {}", 
                lastFailedSync.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                errorMessage,
                consecutiveFailures);
    }
    
    /**
     * Atualiza o número de tentativas de retry para a última entrada
     * 
     * @param retryCount Número de tentativas
     */
    public synchronized void updateLastEntryRetryCount(int retryCount) {
        if (!syncHistory.isEmpty()) {
            SyncStatusEntry lastEntry = syncHistory.peek();
            lastEntry.setRetryCount(retryCount);
        }
    }
    
    /**
     * Adiciona uma entrada ao histórico, mantendo o limite de tamanho
     */
    private void addToHistory(SyncStatusEntry entry) {
        syncHistory.add(entry);
        
        // Mantém o tamanho do histórico limitado
        while (syncHistory.size() > MAX_STATUS_ENTRIES) {
            syncHistory.poll();
        }
    }
    
    /**
     * Retorna o histórico de sincronizações
     */
    public List<SyncStatusEntry> getSyncHistory() {
        return new ArrayList<>(syncHistory);
    }
    
    /**
     * Retorna o status atual da sincronização
     */
    public SyncStatus getCurrentStatus() {
        SyncStatus status = new SyncStatus();
        status.setLastSuccessfulSync(lastSuccessfulSync);
        status.setLastFailedSync(lastFailedSync);
        status.setLastErrorMessage(lastErrorMessage);
        status.setConsecutiveFailures(consecutiveFailures);
        status.setTotalSyncs(totalSyncs);
        status.setSuccessfulSyncs(successfulSyncs);
        status.setSuccessRate(totalSyncs > 0 ? (double) successfulSyncs / totalSyncs : 0);
        status.setHealthy(consecutiveFailures == 0);
        
        return status;
    }
    
    /**
     * Classe para representar uma entrada no histórico de sincronizações
     */
    public static class SyncStatusEntry {
        private final LocalDateTime timestamp;
        private final boolean success;
        private final String errorMessage;
        private int retryCount;
        
        public SyncStatusEntry(LocalDateTime timestamp, boolean success, String errorMessage, int retryCount) {
            this.timestamp = timestamp;
            this.success = success;
            this.errorMessage = errorMessage;
            this.retryCount = retryCount;
        }
        
        public LocalDateTime getTimestamp() {
            return timestamp;
        }
        
        public boolean isSuccess() {
            return success;
        }
        
        public String getErrorMessage() {
            return errorMessage;
        }
        
        public int getRetryCount() {
            return retryCount;
        }
        
        public void setRetryCount(int retryCount) {
            this.retryCount = retryCount;
        }
    }
    
    /**
     * Classe para representar o status atual da sincronização
     */
    public static class SyncStatus {
        private LocalDateTime lastSuccessfulSync;
        private LocalDateTime lastFailedSync;
        private String lastErrorMessage;
        private int consecutiveFailures;
        private int totalSyncs;
        private int successfulSyncs;
        private double successRate;
        private boolean healthy;
        
        // Getters e Setters
        
        public LocalDateTime getLastSuccessfulSync() {
            return lastSuccessfulSync;
        }
        
        public void setLastSuccessfulSync(LocalDateTime lastSuccessfulSync) {
            this.lastSuccessfulSync = lastSuccessfulSync;
        }
        
        public LocalDateTime getLastFailedSync() {
            return lastFailedSync;
        }
        
        public void setLastFailedSync(LocalDateTime lastFailedSync) {
            this.lastFailedSync = lastFailedSync;
        }
        
        public String getLastErrorMessage() {
            return lastErrorMessage;
        }
        
        public void setLastErrorMessage(String lastErrorMessage) {
            this.lastErrorMessage = lastErrorMessage;
        }
        
        public int getConsecutiveFailures() {
            return consecutiveFailures;
        }
        
        public void setConsecutiveFailures(int consecutiveFailures) {
            this.consecutiveFailures = consecutiveFailures;
        }
        
        public int getTotalSyncs() {
            return totalSyncs;
        }
        
        public void setTotalSyncs(int totalSyncs) {
            this.totalSyncs = totalSyncs;
        }
        
        public int getSuccessfulSyncs() {
            return successfulSyncs;
        }
        
        public void setSuccessfulSyncs(int successfulSyncs) {
            this.successfulSyncs = successfulSyncs;
        }
        
        public double getSuccessRate() {
            return successRate;
        }
        
        public void setSuccessRate(double successRate) {
            this.successRate = successRate;
        }
        
        public boolean isHealthy() {
            return healthy;
        }
        
        public void setHealthy(boolean healthy) {
            this.healthy = healthy;
        }
    }
}
