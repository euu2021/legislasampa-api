package br.com.sampachat.api.util;

/**
 * Constantes da aplicação que são usadas em diversos lugares do sistema.
 */
public final class AppConstants {
    
    private AppConstants() {
        // Construtor privado para evitar instanciação
    }
    
    /**
     * Número padrão de resultados por página
     */
    public static final int DEFAULT_PAGE_SIZE = 20;
    
    /**
     * Limite máximo de resultados que podem ser recuperados no total
     */
    public static final int MAX_RESULTS_LIMIT = 1000;
}
