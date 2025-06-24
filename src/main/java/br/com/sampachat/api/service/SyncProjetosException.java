package br.com.sampachat.api.service;

/**
 * Exceção personalizada para erros durante a sincronização de projetos
 */
public class SyncProjetosException extends RuntimeException {
    
    public SyncProjetosException(String message) {
        super(message);
    }
    
    public SyncProjetosException(String message, Throwable cause) {
        super(message, cause);
    }
}
