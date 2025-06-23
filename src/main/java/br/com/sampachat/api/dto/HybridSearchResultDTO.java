package br.com.sampachat.api.dto;

import br.com.sampachat.api.model.Projeto;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
public class HybridSearchResultDTO {
    // A lista de projetos encontrados
    private List<ProjetoResponseDTO> projetos;
    // Um mapa com os filtros que foram aplicados
    private Map<String, List<String>> appliedFilters;
    // Informações de paginação
    private int currentPage;
    private int pageSize;
    private int totalElements;
    private boolean hasMore;
    // Termos para destaque no frontend
    private List<String> highlightTerms;
    
    // Construtor para compatibilidade com código existente
    public HybridSearchResultDTO(List<ProjetoResponseDTO> projetos, Map<String, List<String>> appliedFilters) {
        this.projetos = projetos;
        this.appliedFilters = appliedFilters;
        this.currentPage = 0;
        this.pageSize = projetos.size();
        this.totalElements = projetos.size();
        this.hasMore = false;
        this.highlightTerms = new ArrayList<>();
    }
    
    // Construtor completo com informações de paginação
    public HybridSearchResultDTO(List<ProjetoResponseDTO> projetos, Map<String, List<String>> appliedFilters,
                               int currentPage, int pageSize, int totalElements) {
        this.projetos = projetos;
        this.appliedFilters = appliedFilters;
        this.currentPage = currentPage;
        this.pageSize = pageSize;
        this.totalElements = totalElements;
        this.hasMore = (currentPage + 1) * pageSize < totalElements;
        this.highlightTerms = new ArrayList<>();
    }
    
    // Construtor completo com informações de paginação e termos para destaque
    public HybridSearchResultDTO(List<ProjetoResponseDTO> projetos, Map<String, List<String>> appliedFilters,
                               int currentPage, int pageSize, int totalElements, List<String> highlightTerms) {
        this.projetos = projetos;
        this.appliedFilters = appliedFilters;
        this.currentPage = currentPage;
        this.pageSize = pageSize;
        this.totalElements = totalElements;
        this.hasMore = (currentPage + 1) * pageSize < totalElements;
        this.highlightTerms = highlightTerms;
    }
}