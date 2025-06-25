package br.com.sampachat.api.controller;

import br.com.sampachat.api.dto.HybridSearchResultDTO;
import br.com.sampachat.api.model.Projeto;
import br.com.sampachat.api.service.SearchService;
import br.com.sampachat.api.service.SearchServiceRefactored;
import br.com.sampachat.api.util.AppConstants;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    // Usando o serviço refatorado
    @Autowired
    private SearchServiceRefactored searchService;
    
    @Autowired
    private ObjectMapper objectMapper;

    @GetMapping
    public ResponseEntity<?> searchProjects(
            @RequestParam("q") String query,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "" + AppConstants.DEFAULT_PAGE_SIZE) int size,
            @RequestParam(value = "excludedFilters", required = false) String excludedFiltersJson) {

        if (query == null || query.isBlank()) {
            // Retorna um DTO vazio com informações de paginação
            return ResponseEntity.ok(new HybridSearchResultDTO(List.of(), new HashMap<>(), 0, size, 0));
        }

        // Deserializar os filtros excluídos
        Map<String, List<String>> excludedFilters = new HashMap<>();
        if (excludedFiltersJson != null && !excludedFiltersJson.isEmpty()) {
            try {
                excludedFilters = objectMapper.readValue(excludedFiltersJson, 
                    new TypeReference<Map<String, List<String>>>() {});
            } catch (JsonProcessingException e) {
                System.err.println("Erro ao processar filtros excluídos: " + e.getMessage());
                // Continua com mapa vazio
            }
        }

        // Sempre usa a busca híbrida/inteligente com o serviço refatorado
        HybridSearchResultDTO results = searchService.searchHybridPaged(query, page, size, excludedFilters);
        return ResponseEntity.ok(results);
    }
    
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSearch(
            @RequestParam("q") String query,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "" + AppConstants.DEFAULT_PAGE_SIZE) int size,
            @RequestParam(value = "excludedFilters", required = false) String excludedFiltersJson) {
        
        // Verificação inicial da query
        if (query == null || query.isBlank()) {
            SseEmitter emitter = new SseEmitter();
            try {
                // Retorna um DTO vazio com informações de paginação
                HybridSearchResultDTO emptyResult = new HybridSearchResultDTO(List.of(), new HashMap<>(), 0, size, 0);
                emptyResult.setResultType("exact");
                emitter.send(emptyResult);
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }
        
        // Deserializar os filtros excluídos
        final Map<String, List<String>> finalExcludedFilters = new HashMap<>();
        
        if (excludedFiltersJson != null && !excludedFiltersJson.isEmpty()) {
            try {
                Map<String, List<String>> parsedFilters = objectMapper.readValue(excludedFiltersJson, 
                    new TypeReference<Map<String, List<String>>>() {});
                finalExcludedFilters.putAll(parsedFilters);
            } catch (JsonProcessingException e) {
                System.err.println("Erro ao processar filtros excluídos: " + e.getMessage());
                // Continua com mapa vazio
            }
        }
        
        // Cria um emitter com um timeout de 3 minutos
        final SseEmitter emitter = new SseEmitter(180000L);
        
        // Executa a busca em uma thread separada para não bloquear o thread principal
        CompletableFuture.runAsync(() -> {
            try {
                // Realiza a busca em duas etapas - usando a cópia final dos filtros
                searchService.searchHybridPagedWithSSE(query, page, size, finalExcludedFilters, emitter);
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        
        return emitter;
    }
}