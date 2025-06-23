package br.com.sampachat.api.service;

import br.com.sampachat.api.dto.HybridSearchResultDTO;
import br.com.sampachat.api.dto.ProjetoResponseDTO;
import br.com.sampachat.api.model.Projeto;
import br.com.sampachat.api.model.TipoProposicao;
import br.com.sampachat.api.repository.ProjetoRepository;
import br.com.sampachat.api.util.AppConstants;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Year;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class SearchService {

    // Constantes para limites de resultados
    // Valores definidos na classe AppConstants

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private ProjetoRepository projetoRepository;

    @PersistenceContext
    private EntityManager entityManager;
    
    @Autowired
    private LinkBuilderService linkBuilderService;

    private List<String> todosOsAutores;

    private Set<String> todosOsPartidos;

    private static final Set<String> STOPWORDS = Set.of("de", "da", "do", "dos", "das");
    
    // Constantes para o caso específico do vereador Danilo
    private static final String DANILO_POSTO_SAUDE = "danilo do posto de saude";
    private static final String TERMO_SAUDE = "saude"; // Termo normalizado para exceção
    private static final String TERMO_DANILO = "danilo"; // Termo para identificar busca pelo vereador
    private static final String TERMO_POSTO = "posto"; // Termo para identificar busca pelo vereador
    
    // Lista de termos temáticos que não devem ser usados para extração de autores quando presentes na query
    private static final Set<String> TERMOS_TEMATICOS = Set.of(
        // Termos gerais
        "saude", "educacao", "mulher", "trabalho", "ambiente", "cultura", 
        "transporte", "direitos", "idoso", "crianca", "adolescente", "familia", 
        "habitacao", "moradia", "urbanismo", "seguranca", "comercio", "empresarial",
        "desenvolvimento", "orçamento", "financas", "social", "promocao", "inclusao",
        "assistencia", "politica", "publica", "servico", "verde", "azul", "esporte",
        "juventude", "cidadania", "consumidor", "defesa", "justica", "diversidade",
        "igualdade", "racial", "genero", "acessibilidade", "mobilidade", "transparencia",
        "participacao", "popular", "tecnologia", "inovacao", "economia", "planejamento",
        "emergencia", "urgencia", "posto", "hospital", "acidente", "animal", "pet", "comunidade",
        
        // Termos adicionais de comissões
        "comissao", "comissoes", "urbana", "metropolitana", "meio", "extraordinaria", 
        "relacoes", "internacionais", "administracao", "transito", "atividade", 
        "economica", "constituicao", "legislacao", "participativa", "legislativa",
        "legais", "legal", "fiscalizacao", "investigacao", "processante", "parlamentar",
        "inquerito", "sustentabilidade", "tributos", "finanças", 
        "gestao", "patrimonio", "infancia", "fomento", "direito",
        "projeto", "humanos"
    );

    @PostConstruct
    public void init() {
        todosOsAutores = projetoRepository.findDistinctAutores();
        

        // --- NOVA LÓGICA PARA EXTRAIR PARTIDOS ---
        Pattern partidoPattern = Pattern.compile("\\((.*?)\\)"); // Padrão para pegar texto entre parênteses
        todosOsPartidos = new HashSet<>();
        for (String autorCompleto : todosOsAutores) {
            Matcher matcher = partidoPattern.matcher(autorCompleto);
            if (matcher.find()) {
                // Adiciona o partido em minúsculas à nossa lista
                todosOsPartidos.add(matcher.group(1).toLowerCase());
            }
        }
    }

    // Método para busca híbrida com paginação
    public HybridSearchResultDTO searchHybridPaged(String userQuery, int page, int size, Map<String, List<String>> excludedFilters) {
        try {
            System.out.println("\n\n==== INÍCIO DA BUSCA ====");
            System.out.println("Query original: '" + userQuery + "'");
            System.out.println("Página: " + page + ", Tamanho: " + size);
            System.out.println("Filtros excluídos: " + excludedFilters);

            SearchFilter filter = extractFilters(userQuery, excludedFilters);
            String semanticQuery = filter.getSemanticQuery();
            List<String> exactPhrases = filter.getExactPhrases();

            System.out.println("\n==== EXTRAÇÃO DE FILTROS ====");
            System.out.println("Filtros extraídos: " + filter);
            System.out.println("Query semântica resultante: '" + semanticQuery + "'");
            if (!exactPhrases.isEmpty()) {
                System.out.println("Termos exatos para filtro: " + exactPhrases);
            }

            // Construção da query JPQL para filtros
            StringBuilder jpql = new StringBuilder("SELECT p.id FROM Projeto p WHERE 1=1");
            Map<String, Object> parameters = new HashMap<>();
            if (filter.getTipoProjeto() != null) {
                jpql.append(" AND p.tipo = :tipo");
                parameters.put("tipo", TipoProposicao.valueOf(filter.getTipoProjeto()));
            }
            if (filter.getNumeroProjeto() != null) {
                jpql.append(" AND p.numero = :numero");
                parameters.put("numero", filter.getNumeroProjeto());
            }
            if (!filter.getAutores().isEmpty()) {
                List<String> autoresUnicos = filter.getAutores().stream().distinct().collect(Collectors.toList());
                jpql.append(" AND (");
                for (int i = 0; i < autoresUnicos.size(); i++) {
                    String paramName = "autor" + i;
                    jpql.append("LOWER(p.autor) LIKE LOWER(:").append(paramName).append(")");
                    parameters.put(paramName, "%" + autoresUnicos.get(i) + "%");
                    if (i < autoresUnicos.size() - 1) {
                        jpql.append(" OR ");
                    }
                }
                jpql.append(")");
            }
            if (!filter.getAnos().isEmpty()) {
                jpql.append(" AND p.ano IN :anos");
                parameters.put("anos", filter.getAnos());
            }
            TypedQuery<Integer> idQuery = entityManager.createQuery(jpql.toString(), Integer.class);
            parameters.forEach(idQuery::setParameter);
            List<Integer> relevantIds = idQuery.getResultList();

            if (relevantIds.isEmpty()) {
                System.out.println("Nenhum projeto encontrado após a filtragem inicial.");
                return new HybridSearchResultDTO(List.of(), buildAppliedFiltersMap(filter), page, size, 0,
                                              filter.getExactPhrases()); // Inclui termos exatos para destaque
            }

            // Caso onde não temos query semântica - apenas paginamos por data e número
            if (semanticQuery.isBlank()) {
                String countJpql = "SELECT COUNT(p) FROM Projeto p WHERE p.id IN (:ids)";
                TypedQuery<Long> countQuery = entityManager.createQuery(countJpql, Long.class);
                countQuery.setParameter("ids", relevantIds);
                Long total = countQuery.getSingleResult();
                
                String finalJpql = "SELECT p FROM Projeto p WHERE p.id IN (:ids) ORDER BY p.ano DESC, p.numero DESC";
                TypedQuery<Projeto> finalFilterQuery = entityManager.createQuery(finalJpql, Projeto.class);
                finalFilterQuery.setParameter("ids", relevantIds);
                finalFilterQuery.setFirstResult(page * size);
                finalFilterQuery.setMaxResults(size);
                List<Projeto> projetosFiltrados = finalFilterQuery.getResultList();
                
                // Extrai os termos originais da query do usuário para destaque
                // Dividir a string da consulta em palavras, removendo espaços extras
                String[] palavrasOriginais = userQuery.trim().split("\\s+");
                List<String> termsForHighlight = new ArrayList<>();
                
                // Adiciona todas as palavras da consulta original que tenham mais de 1 caractere
                for (String palavra : palavrasOriginais) {
                    if (palavra.length() > 1) {
                        // Não adiciona termos duplicados
                        if (!termsForHighlight.contains(palavra)) {
                            termsForHighlight.add(palavra);
                        }
                    }
                }
                
                // Adiciona os termos exatos (entre aspas)
                for (String exactPhrase : filter.getExactPhrases()) {
                    if (!termsForHighlight.contains(exactPhrase)) {
                        termsForHighlight.add(exactPhrase);
                    }
                }
                
                // Converte a lista de Projeto para ProjetoResponseDTO com os links
                List<ProjetoResponseDTO> projetosDTO = projetosFiltrados.stream()
                    .map(projeto -> {
                        String linkSpLegis = linkBuilderService.buildSpLegisLink(projeto.getTipo(), projeto.getNumero(), projeto.getAno());
                        String linkPortal = linkBuilderService.buildPortalLink(projeto.getTipo(), projeto.getNumero(), projeto.getAno());
                        String linkPdf = linkBuilderService.buildPdfLink(projeto.getTipo(), projeto.getNumero(), projeto.getAno());
                        return new ProjetoResponseDTO(projeto, linkSpLegis, linkPortal, linkPdf);
                    })
                    .collect(Collectors.toList());
                
                return new HybridSearchResultDTO(
                    projetosDTO, 
                    buildAppliedFiltersMap(filter),
                    page,
                    size,
                    total.intValue(),
                    termsForHighlight
                );
            }

            // Preparar termos de busca para a query
            List<String> queryTerms = Arrays.stream(semanticQuery.trim().split("\\s+"))
                    .filter(term -> term.length() > 1)
                    .map(this::normalizeText) // Normaliza cada termo da query
                    .collect(Collectors.toList());
            
            // Adicionar termos exatos à lista para destaque
            if (!filter.getExactPhrases().isEmpty()) {
                for (String exactPhrase : filter.getExactPhrases()) {
                    String exactPhraseNormalizada = normalizeText(exactPhrase);
                    if (!queryTerms.contains(exactPhraseNormalizada)) {
                        queryTerms.add(exactPhraseNormalizada);
                    }
                    
                    String[] palavras = exactPhraseNormalizada.split("\\s+");
                    for (String palavra : palavras) {
                        if (palavra.length() > 1 && !queryTerms.contains(palavra)) {
                            queryTerms.add(palavra);
                        }
                    }
                }
            }
            
            // SOLUÇÃO 1: "Exatos Primeiro" - Buscar primeiro todos os matches exatos
            System.out.println("\n==== IMPLEMENTANDO SOLUÇÃO 'EXATOS PRIMEIRO' ====");
            System.out.println("Buscando matches exatos para os termos: " + queryTerms);

            // 1. Construir SQL para buscar matches exatos
            StringBuilder exactMatchSql = new StringBuilder("SELECT * FROM projetos WHERE 1=1");
            
            // Aplicar filtro de IDs se existir
            if (!relevantIds.isEmpty()) {
                exactMatchSql.append(" AND id IN (:ids)");
            }
            
            // Construir condição para cada termo
            exactMatchSql.append(" AND (");
            for (int i = 0; i < queryTerms.size(); i++) {
                if (i > 0) {
                    exactMatchSql.append(" OR ");
                }
                // Usar LIKE para correspondência exata
                exactMatchSql.append("(")
                            .append("LOWER(coalesce(ementa,'')) LIKE :term").append(i)
                            .append(" OR LOWER(coalesce(palavras_chave,'')) LIKE :term").append(i)
                            .append(" OR LOWER(coalesce(autor,'')) LIKE :term").append(i)
                            .append(")");
            }
            exactMatchSql.append(")");
            
            // Ordenar por ano e número
            exactMatchSql.append(" ORDER BY ano DESC, numero DESC");
            
            System.out.println("SQL para busca exata: " + exactMatchSql.toString());
            System.out.println("Parâmetros de busca:");
            for (int i = 0; i < queryTerms.size(); i++) {
                System.out.println("  term" + i + ": \"%" + queryTerms.get(i) + "%\"");
            }
            
            // 2. Executar a busca exata
            jakarta.persistence.Query exactMatchQuery = entityManager.createNativeQuery(exactMatchSql.toString(), Projeto.class);
            
            // Definir parâmetros
            if (!relevantIds.isEmpty()) {
                exactMatchQuery.setParameter("ids", relevantIds);
            }
            
            for (int i = 0; i < queryTerms.size(); i++) {
                exactMatchQuery.setParameter("term" + i, "%" + queryTerms.get(i) + "%");
            }
            
            @SuppressWarnings("unchecked")
            List<Projeto> exactMatchesFromDB = exactMatchQuery.getResultList();
            System.out.println("Resultados exatos encontrados no banco: " + exactMatchesFromDB.size());

            // Validação extra para garantir correspondência exata
            List<Projeto> exactMatches = exactMatchesFromDB.stream()
                .filter(projeto -> {
                    // Combinar e normalizar todos os campos de texto do projeto
                    String textoNormalizado = normalizeText(
                        (projeto.getEmenta() != null ? projeto.getEmenta() : "") + " " + 
                        (projeto.getPalavrasChave() != null ? projeto.getPalavrasChave().replace("|", " ") : "") + " " +
                        (projeto.getAutor() != null ? projeto.getAutor() : "")
                    );
                    
                    // Verificar se o texto contém pelo menos um dos termos da query
                    boolean containsAnyTerm = queryTerms.stream().anyMatch(textoNormalizado::contains);
                    
                    if (!containsAnyTerm) {
                        System.out.println("FALSO POSITIVO REMOVIDO: " + projeto.getTipo() + " " +
                                          projeto.getNumero() + "/" + projeto.getAno() +
                                          " não contém nenhum dos termos da pesquisa");
                    }
                    
                    return containsAnyTerm;
                })
                .collect(Collectors.toList());
            
            System.out.println("Resultados exatos após validação: " + exactMatches.size());

            System.out.println("Encontrados " + exactMatches.size() + " matches exatos.");


            // 3. Se necessário, complementar com resultados semânticos
            List<Projeto> semanticResults = new ArrayList<>();
            
            if (exactMatches.size() < AppConstants.MAX_RESULTS_LIMIT) {
                System.out.println("Complementando com busca semântica...");

                // Gerar embeddings para a query
                float[] queryEmbedding = embeddingService.generateEmbeddings(List.of(semanticQuery))[0];
                String vectorString = Arrays.toString(queryEmbedding);
                
                // Extrair IDs dos resultados exatos para excluí-los da busca semântica
                List<Integer> exactMatchIds = exactMatches.stream()
                    .map(Projeto::getId)
                    .collect(Collectors.toList());
                
                // Construir SQL para busca semântica
                String semanticSql;
                if (exactMatchIds.isEmpty()) {
                    // Se não houver matches exatos, buscar todos os relevantIds
                    semanticSql = "SELECT * FROM projetos WHERE id IN (:ids) ORDER BY embedding <=> CAST(:queryVector AS vector) LIMIT " + 
                        (AppConstants.MAX_RESULTS_LIMIT - exactMatches.size());
                } else {
                    // Se houver matches exatos, excluí-los da busca semântica
                    semanticSql = "SELECT * FROM projetos WHERE id IN (:ids) AND id NOT IN (:exactIds) " + 
                        "ORDER BY embedding <=> CAST(:queryVector AS vector) LIMIT " + 
                        (AppConstants.MAX_RESULTS_LIMIT - exactMatches.size());
                }
                
                jakarta.persistence.Query semanticQueryObj = entityManager.createNativeQuery(semanticSql, Projeto.class);
                semanticQueryObj.setParameter("ids", relevantIds);
                semanticQueryObj.setParameter("queryVector", vectorString);
                
                if (!exactMatchIds.isEmpty()) {
                    semanticQueryObj.setParameter("exactIds", exactMatchIds);
                }
                
                @SuppressWarnings("unchecked")
                List<Projeto> semanticOnlyResults = semanticQueryObj.getResultList();
                
                System.out.println("Encontrados " + semanticOnlyResults.size() + " resultados semânticos adicionais.");

                // Adicionar resultados semânticos à lista completa
                semanticResults.addAll(semanticOnlyResults);
                
                System.out.println("\n==== RESULTADOS SEMÂNTICOS ====");
                System.out.println("Total de resultados semânticos: " + semanticResults.size());

            }

            // 4. Combinar resultados exatos e semânticos
            
            // 4. Combinar resultados exatos e semânticos
            List<Projeto> finalRankedResults = new ArrayList<>(exactMatches);
            finalRankedResults.addAll(semanticResults);
            
            System.out.println("Total de resultados combinados: " + finalRankedResults.size() +
                               " (" + exactMatches.size() + " exatos, " + semanticResults.size() + " semânticos)");
            
            // 5. Re-ranking dos resultados
            // Mapas para armazenar métricas de rankeamento para cada projeto
            Map<Projeto, Integer> uniqueTermsCountMap = new HashMap<>(); // Número de termos únicos presentes
            Map<Projeto, Integer> totalOccurrencesMap = new HashMap<>(); // Número total de ocorrências dos termos
            
            // Calcular métricas para cada resultado
            for (Projeto projeto : finalRankedResults) {
                String textoCompleto = String.join(" ",
                        projeto.getEmenta() != null ? projeto.getEmenta() : "",
                        projeto.getPalavrasChave() != null ? projeto.getPalavrasChave().replace("|", " ") : ""
                );
                // Normaliza o texto do projeto
                String textoCompletoNormalizado = normalizeText(textoCompleto);
                
                // Conta quantos termos únicos da query estão presentes no texto
                int uniqueTermsCount = 0;
                int totalOccurrences = 0;
                
                for (String term : queryTerms) {
                    // Termo já está normalizado
                    if (textoCompletoNormalizado.contains(term)) {
                        uniqueTermsCount++; // Incrementa contagem de termos únicos
                        
                        // Conta quantas vezes este termo aparece no texto
                        int lastIndex = 0;
                        int count = 0;
                        while (lastIndex != -1) {
                            lastIndex = textoCompletoNormalizado.indexOf(term, lastIndex);
                            if (lastIndex != -1) {
                                count++;
                                lastIndex += term.length();
                            }
                        }
                        
                        totalOccurrences += count; // Adiciona à contagem total de ocorrências
                    }
                }
                
                // Armazena as métricas
                uniqueTermsCountMap.put(projeto, uniqueTermsCount);
                totalOccurrencesMap.put(projeto, totalOccurrences);
            }
            
            // Ordenar os resultados combinados (exactMatches primeiro, depois semânticos)
            finalRankedResults.sort((p1, p2) -> {
                // 1. Resultados exatos sempre vêm antes dos resultados semânticos
                boolean p1IsExact = exactMatches.contains(p1);
                boolean p2IsExact = exactMatches.contains(p2);
                
                if (p1IsExact && !p2IsExact) {
                    return -1; // p1 é exato, p2 não é
                } else if (!p1IsExact && p2IsExact) {
                    return 1;  // p2 é exato, p1 não é
                }
                
                // 2. Entre resultados do mesmo tipo, ordena por termos únicos
                int compareByUniqueTerms = Integer.compare(
                        uniqueTermsCountMap.getOrDefault(p2, 0),
                        uniqueTermsCountMap.getOrDefault(p1, 0)
                );
                
                if (compareByUniqueTerms != 0) {
                    return compareByUniqueTerms;
                }
                
                // 3. Se empate em termos únicos, compara pelo total de ocorrências
                int compareByTotalOccurrences = Integer.compare(
                        totalOccurrencesMap.getOrDefault(p2, 0),
                        totalOccurrencesMap.getOrDefault(p1, 0)
                );
                
                if (compareByTotalOccurrences != 0) {
                    return compareByTotalOccurrences;
                }
                
                // 4. Se ainda houver empate, ordena por ano/número
                int compareByAno = Integer.compare(
                        p2.getAno() != null ? p2.getAno() : 0,
                        p1.getAno() != null ? p1.getAno() : 0
                );
                
                if (compareByAno != 0) {
                    return compareByAno;
                }
                
                return Integer.compare(
                        p2.getNumero() != null ? p2.getNumero() : 0,
                        p1.getNumero() != null ? p1.getNumero() : 0
                );
            });
            

            System.out.println("\n==== CLASSIFICAÇÃO DE RESULTADOS ====");
            System.out.println("Termos da query para verificação: " + queryTerms);


            // Ordenar os resultados combinados (exactMatches primeiro, depois semânticos)
            finalRankedResults.sort((p1, p2) -> {
                // 1. Resultados exatos sempre vêm antes dos resultados semânticos
                boolean p1IsExact = exactMatches.contains(p1);
                boolean p2IsExact = exactMatches.contains(p2);
                
                if (p1IsExact && !p2IsExact) {
                    return -1; // p1 é exato, p2 não é
                } else if (!p1IsExact && p2IsExact) {
                    return 1;  // p2 é exato, p1 não é
                }
                
                // 2. Dentro de cada grupo, ordenar por ano (mais recente primeiro)
                int compareByAno = Integer.compare(
                        p2.getAno() != null ? p2.getAno() : 0,
                        p1.getAno() != null ? p1.getAno() : 0
                );
                
                if (compareByAno != 0) {
                    return compareByAno;
                }
                
                // 3. Se mesmo ano, ordenar por número (maior primeiro)
                return Integer.compare(
                        p2.getNumero() != null ? p2.getNumero() : 0,
                        p1.getNumero() != null ? p1.getNumero() : 0
                );
            });
            
            // Aplicar filtro adicional para termos exatos se necessário
            if (!filter.getExactPhrases().isEmpty()) {

                List<Projeto> exactMatched = new ArrayList<>();
                

                for (Projeto projeto : finalRankedResults) {

                    boolean matchesAllExactPhrases = true;
                    
                    // Preparar os campos do projeto para verificação
                    String ementaNormalizada = normalizeText(projeto.getEmenta() != null ? projeto.getEmenta() : "");
                    String palavrasChaveNormalizadas = normalizeText(projeto.getPalavrasChave() != null ? 
                                                        projeto.getPalavrasChave().replace("|", " ") : "");
                    String autorNormalizado = normalizeText(projeto.getAutor() != null ? projeto.getAutor() : "");
                    String anoStr = projeto.getAno() != null ? projeto.getAno().toString() : "";
                    String numeroStr = projeto.getNumero() != null ? projeto.getNumero().toString() : "";
                    String tipoStr = projeto.getTipo() != null ? normalizeText(projeto.getTipo().toString()) : "";
                    
                    // Combinar todos os campos para verificar a correspondência
                    String todosOsCampos = ementaNormalizada + " " + 
                                           palavrasChaveNormalizadas + " " + 
                                           autorNormalizado + " " + 
                                           anoStr + " " + 
                                           numeroStr + " " + 
                                           tipoStr;
                    

                    // Verificar cada termo exato
                    for (String exactPhrase : filter.getExactPhrases()) {
                        String exactPhraseNormalizada = normalizeText(exactPhrase);
                        

                        // Verificar se o termo exato está presente em algum dos campos
                        if (!ementaNormalizada.contains(exactPhraseNormalizada) && 
                            !palavrasChaveNormalizadas.contains(exactPhraseNormalizada) && 
                            !autorNormalizado.contains(exactPhraseNormalizada) && 
                            !anoStr.contains(exactPhrase) &&  // Não normalizar números
                            !numeroStr.contains(exactPhrase) && // Não normalizar números
                            !tipoStr.contains(exactPhraseNormalizada)) {
                            
                            matchesAllExactPhrases = false;
                            break;
                        }
                    }
                    
                    if (matchesAllExactPhrases) {
                        exactMatched.add(projeto);
                    }
                }
                

                // Substituir a lista de resultados pelos que contêm os termos exatos
                finalRankedResults = exactMatched;
                System.out.println("Após filtro de termos exatos: " + finalRankedResults.size() + " resultados encontrados");
            }
            
            // Total de resultados para informações de paginação
            int totalResults = finalRankedResults.size();
            
            // Aplica a paginação na lista já ordenada
            int startIndex = page * size;
            int endIndex = Math.min(startIndex + size, totalResults);
            
            // Verifica se o índice inicial está dentro dos limites
            List<Projeto> pagedResults;
            if (startIndex >= totalResults) {
                pagedResults = List.of(); // Página vazia se estiver além do total
            } else {
                pagedResults = finalRankedResults.subList(startIndex, endIndex);
            }


            // Extrai os termos originais da query do usuário para destaque
            // Dividir a string da consulta em palavras, removendo espaços extras
            String[] palavrasOriginais = userQuery.trim().split("\\s+");
            List<String> termsForHighlight = new ArrayList<>();
            
            // Adiciona todas as palavras da consulta original que tenham mais de 1 caractere
            for (String palavra : palavrasOriginais) {
                if (palavra.length() > 1) {
                    // Não adiciona termos duplicados
                    if (!termsForHighlight.contains(palavra)) {
                        termsForHighlight.add(palavra);
                    }
                }
            }
            
            // Adiciona os termos exatos (entre aspas)
            if (!filter.getExactPhrases().isEmpty()) {
                for (String exactPhrase : filter.getExactPhrases()) {
                    if (!termsForHighlight.contains(exactPhrase)) {
                        termsForHighlight.add(exactPhrase);
                    }
                }
            }
            
            System.out.println("Termos para destaque: " + termsForHighlight);

            // Converte a lista de Projeto para ProjetoResponseDTO com os links
            List<ProjetoResponseDTO> projetosDTO = pagedResults.stream()
                .map(projeto -> {
                    String linkSpLegis = linkBuilderService.buildSpLegisLink(projeto.getTipo(), projeto.getNumero(), projeto.getAno());
                    String linkPortal = linkBuilderService.buildPortalLink(projeto.getTipo(), projeto.getNumero(), projeto.getAno());
                    String linkPdf = linkBuilderService.buildPdfLink(projeto.getTipo(), projeto.getNumero(), projeto.getAno());
                    return new ProjetoResponseDTO(projeto, linkSpLegis, linkPortal, linkPdf);
                })
                .collect(Collectors.toList());
                
            System.out.println("\n==== RESULTADOS FINAIS ====");
            System.out.println("Total de resultados: " + totalResults);


            System.out.println("==== FIM DA BUSCA ====\n");

            return new HybridSearchResultDTO(
                projetosDTO,
                buildAppliedFiltersMap(filter),
                page,
                size,
                totalResults,
                termsForHighlight
            );

        } catch (Exception e) {
            System.err.println("Erro durante a busca híbrida: " + e.getMessage());
            e.printStackTrace();
            return new HybridSearchResultDTO(List.of(), Collections.emptyMap(), page, size, 0, List.of());
        }
    }

    // Método para busca FTS com paginação
    public List<Projeto> searchFTSPaged(String userQuery, int page, int size) {
        try {
            System.out.println("\n==== BUSCA FTS ====");
            System.out.println("Query original para FTS: '" + userQuery + "'");

            String[] termos = userQuery.trim().split("\\s+");
            System.out.println("Termos extraídos: " + Arrays.toString(termos));

            // Mostrar cada termo normalizado
            System.out.println("Termos normalizados:");
            for (String termo : termos) {
                if (!termo.isBlank()) {
                    String normalizado = normalizeText(termo);
                    System.out.println("  '" + termo + "' -> '" + normalizado + "'");
                }
            }
            
            String ftsQuery = Arrays.stream(userQuery.trim().split("\\s+"))
                    .filter(term -> !term.isBlank())
                    .map(this::normalizeText) // Normaliza os termos da query
                    .map(term -> term.replace("'", "''") + ":*")
                    .collect(Collectors.joining(" & "));

            System.out.println("Query FTS final: '" + ftsQuery + "'");

            if (ftsQuery.isBlank()) {
                System.out.println("Query FTS está vazia, retornando lista vazia");
                return List.of();
            }

            String sql = "SELECT * FROM projetos " +
                    "WHERE ts_search @@ to_tsquery('portuguese', '" + ftsQuery + "') " +
                    "ORDER BY ano DESC, numero DESC " +
                    "LIMIT " + size + " OFFSET " + (page * size);

            System.out.println("SQL completo: " + sql);

            jakarta.persistence.Query query = entityManager.createNativeQuery(sql, Projeto.class);

            @SuppressWarnings("unchecked")
            List<Projeto> resultados = query.getResultList();

            System.out.println(resultados.size() + " resultados encontrados na página " + page + " (busca exata).");


            return resultados;
        } catch (Exception e) {
            System.err.println("Erro durante a busca FTS: " + e.getMessage());
            e.printStackTrace();
            return List.of();
        }
    }
    
    // Método para contar o total de resultados FTS (para paginação)
    public int countFTSResults(String userQuery) {
        try {
            String ftsQuery = Arrays.stream(userQuery.trim().split("\\s+"))
                    .filter(term -> !term.isBlank())
                    .map(this::normalizeText) // Normaliza os termos da query
                    .map(term -> term.replace("'", "''") + ":*")
                    .collect(Collectors.joining(" & "));

            if (ftsQuery.isBlank()) {
                return 0;
            }

            String sql = "SELECT COUNT(*) FROM projetos " +
                    "WHERE ts_search @@ to_tsquery('portuguese', '" + ftsQuery + "')";

            Object result = entityManager.createNativeQuery(sql).getSingleResult();
            return ((Number) result).intValue();
        } catch (Exception e) {
            System.err.println("Erro ao contar resultados FTS: " + e.getMessage());
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * Normaliza o texto removendo acentos, convertendo para minúsculas e removendo caracteres especiais
     * @param texto Texto a ser normalizado
     * @return Texto normalizado
     */
    private String normalizeText(String texto) {
        if (texto == null) {
            System.out.println("Normalização: texto nulo retornando string vazia");
            return "";
        }
        

        // Converte para minúsculas
        String textoNormalizado = texto.toLowerCase();
        

        // Remove acentos
        textoNormalizado = Normalizer.normalize(textoNormalizado, Normalizer.Form.NFD)
                           .replaceAll("\\p{M}", ""); // remove marcas diacríticas
        

        return textoNormalizado;
    }
    
    // --- MÉTODOS LEGADOS (MANTIDOS POR COMPATIBILIDADE) ---

    /**
     * @deprecated Use searchHybridPaged() instead
     */

    @Deprecated
    public HybridSearchResultDTO searchHybrid(String userQuery) {
        return searchHybridPaged(userQuery, 0, AppConstants.DEFAULT_PAGE_SIZE);
    }

    // Versão original do método mantida para compatibilidade
    public HybridSearchResultDTO searchHybridPaged(String userQuery, int page, int size) {
        return searchHybridPaged(userQuery, page, size, new HashMap<>());
    }

    private SearchFilter extractFilters(String query) {
        return extractFilters(query, new HashMap<>());
    }

    private SearchFilter extractFilters(String query, Map<String, List<String>> excludedFilters) {
        // Verificação para consultas muito curtas ou com caracteres especiais isolados
        if (query == null || query.trim().length() <= 1 || query.trim().matches("^[\\-\\+\\*\\/\\\\]$")) {
            System.out.println("Consulta muito curta ou caractere especial isolado: '" + query + "'. Tratando como consulta sem filtros.");
            return new SearchFilter(new ArrayList<>(), new ArrayList<>(), null, null, query.trim());
        }
        
        // Extrair termos entre aspas
        Pattern quotePattern = Pattern.compile("\"([^\"]*)\"");
        Matcher quoteMatcher = quotePattern.matcher(query);
        List<String> exactPhrases = new ArrayList<>();
        
        while (quoteMatcher.find()) {
            String exactPhrase = quoteMatcher.group(1).trim();
            if (!exactPhrase.isEmpty()) {
                exactPhrases.add(exactPhrase);
                System.out.println("Termo exato encontrado: '" + exactPhrase + "'");
            }
        }
        
        // Remover aspas da query mas manter o conteúdo para extração de entidades
        String queryWithoutQuotes = query.replaceAll("\"", "");
        
        // Substituir hífens soltos por espaços para evitar confusão com autor "Executivo - RICARDO NUNES"
        // Um hífen solto é aquele que tem espaços dos dois lados
        queryWithoutQuotes = queryWithoutQuotes.replaceAll("\\s-\\s", " ");
        
        String lowerCaseQuery = " " + queryWithoutQuotes.toLowerCase() + " ";
        List<String> autoresEncontrados = new ArrayList<>();
        List<Integer> anosEncontrados = new ArrayList<>();
        Integer numeroProjetoEncontrado = null;
        String tipoProjetoEncontrado = null;

        // --- ORDEM DE EXTRAÇÃO LÓGICA E REFINADA ---

        // 1. Extrai Tipo de Projeto e Número (suportando diversos formatos como PL123, PL 123, PL123/2025, etc)
        Map<String, String> tiposProjetoMap = new HashMap<>();
        
        // Projeto de Lei
        tiposProjetoMap.put("pl", "PL");
        tiposProjetoMap.put("projeto de lei", "PL");
        tiposProjetoMap.put("projetos de lei", "PL");
        tiposProjetoMap.put("pls", "PL");
        
        // Projeto de Decreto Legislativo
        tiposProjetoMap.put("pdl", "PDL");
        tiposProjetoMap.put("projeto de decreto legislativo", "PDL");
        tiposProjetoMap.put("projetos de decreto legislativo", "PDL");
        tiposProjetoMap.put("pdls", "PDL");
        
        // Projeto de Lei Orgânica
        tiposProjetoMap.put("plo", "PLO");
        tiposProjetoMap.put("projeto de lei orgânica", "PLO");
        tiposProjetoMap.put("projeto de lei organica", "PLO");
        tiposProjetoMap.put("projetos de lei orgânica", "PLO");
        tiposProjetoMap.put("projetos de lei organica", "PLO");
        tiposProjetoMap.put("plos", "PLO");
        
        // Projeto de Resolução
        tiposProjetoMap.put("pr", "PR");
        tiposProjetoMap.put("projeto de resolução", "PR");
        tiposProjetoMap.put("projeto de resolucao", "PR");
        tiposProjetoMap.put("projetos de resolução", "PR");
        tiposProjetoMap.put("projetos de resolucao", "PR");
        tiposProjetoMap.put("prs", "PR");
        
        // NOVA LÓGICA: Primeiro procura por padrões completos como "PL123" ou "PL 123/2025"
        boolean formatoComplexoEncontrado = false;
        
        // Padrão que captura todos os formatos: PL123, PL 123, PL123/2025, PL 123/2025, PL 123 2025
        // Grupo 1: Tipo de projeto (PL, PDL, etc)
        // Grupo 2: Número do projeto
        // Grupo 3: Ano (opcional)
        String tiposKeys = String.join("|", tiposProjetoMap.keySet());
        Pattern formatoCompletoPattern = Pattern.compile(
            "\\b(" + tiposKeys + ")\\s*(\\d{1,4})(?:\\s*\\/\\s*(\\d{4})|\\s+(\\d{4}))?\\b", 
            Pattern.CASE_INSENSITIVE
        );
        
        Matcher formatoCompletoMatcher = formatoCompletoPattern.matcher(lowerCaseQuery);
        if (formatoCompletoMatcher.find()) {
            formatoComplexoEncontrado = true;
            
            // Extrai o tipo de projeto
            String tipoEncontrado = formatoCompletoMatcher.group(1).toLowerCase();
            tipoProjetoEncontrado = tiposProjetoMap.get(tipoEncontrado);
            
            // Extrai o número do projeto
            numeroProjetoEncontrado = Integer.parseInt(formatoCompletoMatcher.group(2));
            
            // Extrai o ano, se presente (pode estar no grupo 3 ou 4, dependendo do formato)
            String anoStr = formatoCompletoMatcher.group(3);
            if (anoStr == null) {
                anoStr = formatoCompletoMatcher.group(4);
            }
            
            if (anoStr != null) {
                int ano = Integer.parseInt(anoStr);
                if (ano >= 1990 && ano <= 2025) {
                    anosEncontrados.add(ano);
                }
            }
            
            System.out.println("Formato completo encontrado: Tipo='" + tipoProjetoEncontrado +
                              "', Número=" + numeroProjetoEncontrado +
                              (anoStr != null ? ", Ano=" + anoStr : ""));
            
            // Remove todo o texto correspondente para não confundir
            lowerCaseQuery = lowerCaseQuery.replace(formatoCompletoMatcher.group(0), " ");
        }
        
        // Extração tradicional de tipo de projeto (apenas se não encontramos um formato completo)
        if (!formatoComplexoEncontrado) {
            // Criar pattern para todos os tipos (abreviados e extensos)
            String tiposPattern = "\\b(" + String.join("|", tiposProjetoMap.keySet()) + ")\\b";
            Pattern tipoPattern = Pattern.compile(tiposPattern);
            Matcher tipoMatcher = tipoPattern.matcher(lowerCaseQuery);
            
            if (tipoMatcher.find()) {
                String tipoEncontrado = tipoMatcher.group(1);
                tipoProjetoEncontrado = tiposProjetoMap.get(tipoEncontrado);
                System.out.println("Tipo de projeto encontrado: '" + tipoEncontrado + "' -> " + tipoProjetoEncontrado);

                // Remove a primeira ocorrência para não confundir
                lowerCaseQuery = lowerCaseQuery.replace(tipoEncontrado, " ");
            }
        }

        // 1.5 (NOVO) Extrai intervalo de anos - deve vir antes da extração de anos individuais
        // Padrões suportados:
        // - "entre 2015 e 2018"
        // - "entre os anos de 2015 e 2018"
        // - "entre os anos 2015 e 2018"
        // - "de 2015 a 2018"
        // - "2015 a 2018"
        // - "2015-2018"
        Pattern intervaloAnosPattern = Pattern.compile(
            "(?:entre(?: os anos(?: de)?)? (\\d{4}) e (\\d{4})" +  // Padrão "entre X e Y"
            "|de (\\d{4}) a (\\d{4})" +                            // Padrão "de X a Y"
            "|(\\d{4}) a (\\d{4})" +                               // Padrão "X a Y" 
            "|(\\d{4})-(\\d{4}))"                                  // Padrão "X-Y"
        );
        Matcher intervaloAnosMatcher = intervaloAnosPattern.matcher(lowerCaseQuery);
        
        if (intervaloAnosMatcher.find()) {
            try {
                // Capturar o texto completo para substituição posterior
                String textoCompleto = intervaloAnosMatcher.group(0);
                System.out.println("Texto do intervalo capturado: '" + textoCompleto + "'");

                // Para depuração, vamos imprimir todos os grupos capturados
                System.out.println("DEBUG - Todos os grupos capturados:");
                for (int i = 0; i <= intervaloAnosMatcher.groupCount(); i++) {
                    System.out.println("Grupo " + i + ": " + (intervaloAnosMatcher.group(i) != null ?
                                                            "'" + intervaloAnosMatcher.group(i) + "'" :
                                                            "null"));
                }
                
                // Extrair os anos (verificando qual padrão foi capturado)
                int anoInicio, anoFim;
                
                if (intervaloAnosMatcher.group(1) != null) {
                    // Padrão "entre X e Y"
                    anoInicio = Integer.parseInt(intervaloAnosMatcher.group(1));
                    anoFim = Integer.parseInt(intervaloAnosMatcher.group(2));
                } else if (intervaloAnosMatcher.group(3) != null) {
                    // Padrão "de X a Y"
                    anoInicio = Integer.parseInt(intervaloAnosMatcher.group(3));
                    anoFim = Integer.parseInt(intervaloAnosMatcher.group(4));
                } else if (intervaloAnosMatcher.group(5) != null) {
                    // Padrão "X a Y"
                    anoInicio = Integer.parseInt(intervaloAnosMatcher.group(5));
                    anoFim = Integer.parseInt(intervaloAnosMatcher.group(6));
                } else {
                    // Padrão "X-Y"
                    anoInicio = Integer.parseInt(intervaloAnosMatcher.group(7));
                    anoFim = Integer.parseInt(intervaloAnosMatcher.group(8));
                }
                
                System.out.println("Anos extraídos: " + anoInicio + " e " + anoFim);

                // Garantir que anoInicio <= anoFim
                if (anoInicio > anoFim) {
                    int temp = anoInicio;
                    anoInicio = anoFim;
                    anoFim = temp;
                }
                
                // Adicionar todos os anos no intervalo
                for (int ano = anoInicio; ano <= anoFim; ano++) {
                    anosEncontrados.add(ano);
                }
                
                System.out.println("Intervalo completo de anos: " + anosEncontrados);

                // Remover o texto do intervalo da consulta (usando replace para remover exatamente o texto encontrado)
                lowerCaseQuery = lowerCaseQuery.replace(textoCompleto, " ");
                System.out.println("Query após remoção do intervalo: '" + lowerCaseQuery + "'");

            } catch (Exception e) {
                System.err.println("Erro ao processar intervalo de anos: " + e.getMessage());
                e.printStackTrace();
            }
        }

        // 2. Extrai Anos individuais (1990-2099) - só deve executar se não encontrou um intervalo
        if (anosEncontrados.isEmpty()) {
            Pattern anoPattern = Pattern.compile("\\b((?:199|20[0-2])\\d)\\b"); // Padrão para anos 1990-2029
            Matcher anoMatcher = anoPattern.matcher(lowerCaseQuery);
            while (anoMatcher.find()) {
                int ano = Integer.parseInt(anoMatcher.group(1));
                if (ano >= 1990 && ano <= 2025) { // Valida o intervalo
                    anosEncontrados.add(ano);
                    // Remove o ano encontrado para não ser confundido com número de projeto
                    lowerCaseQuery = lowerCaseQuery.replace(anoMatcher.group(1), " ");
                }
            }
            
            // Lógica para "nos últimos X anos"
            Pattern anosRecentesPattern = Pattern.compile("últimos (\\d+) anos");
            Matcher anosRecentesMatcher = anosRecentesPattern.matcher(lowerCaseQuery);
            if (anosRecentesMatcher.find()) {
                int numAnos = Integer.parseInt(anosRecentesMatcher.group(1));
                int anoAtual = Year.now().getValue();
                for (int i = 0; i < numAnos; i++) {
                    anosEncontrados.add(anoAtual - i);
                }
                lowerCaseQuery = anosRecentesMatcher.replaceAll(" ");
            }
        }


        // 3. Extrai Número do Projeto (1-1500) - apenas se não foi encontrado no formato completo
        if (!formatoComplexoEncontrado && numeroProjetoEncontrado == null) {
            Pattern numPattern = Pattern.compile("\\b(\\d{1,4})\\b"); // Procura por números de até 4 dígitos
            Matcher numMatcher = numPattern.matcher(lowerCaseQuery);
            while (numMatcher.find()) {
                int num = Integer.parseInt(numMatcher.group(1));
                // Apenas considera se for um número de projeto válido
                if (num > 0 && num <= 1500) {
                    numeroProjetoEncontrado = num;
                    // Remove o número para não poluir a busca semântica
                    lowerCaseQuery = lowerCaseQuery.replace(numMatcher.group(1), " ");
                    break; // Pega apenas a primeira ocorrência válida
                }
            }
        }

        // 4. Extrai Partidos
        for (String partido : todosOsPartidos) {
            String partidoNormalizado = normalizeText(partido);
            String queryNormalizada = normalizeText(lowerCaseQuery);
            if (queryNormalizada.contains(" " + partidoNormalizado + " ")) {
                autoresEncontrados.add("(" + partido + ")");
                lowerCaseQuery = lowerCaseQuery.replace(" " + partido + " ", " ");
            }
        }

        // 5. Extrai Autores - com verificação de nome completo e tratamento de prefixos
        // Normaliza a query para análise
        String queryNormalizada = normalizeText(lowerCaseQuery);
        
        // Verifica se a query contém "saude"
        boolean queryContemSaude = queryNormalizada.contains(" " + TERMO_SAUDE + " ");
        
        // Verifica se a query parece ser especificamente sobre o vereador Danilo
        boolean buscaPeloVereador = queryNormalizada.contains(" " + TERMO_DANILO + " ") && 
                                   (queryNormalizada.contains(" " + TERMO_POSTO + " ") || 
                                    queryNormalizada.contains(" " + TERMO_SAUDE + " "));
        
        if (queryContemSaude && !buscaPeloVereador) {
            System.out.println("AVISO: Query contém 'saude' mas não parece ser sobre o vereador - ignorando matches para Danilo do Posto de Saúde");
        }
        
        if (buscaPeloVereador) {
            System.out.println("AVISO: Detectada busca específica pelo vereador Danilo do Posto de Saúde");
        }
        
        // Para evitar duplicações
        Set<String> autoresJaAdicionados = new HashSet<>();
        
        // Armazena os tokens que devem ser removidos da query após encontrar todos os autores
        Map<String, Boolean> tokensParaRemover = new HashMap<>();
        
        // Flag para indicar se um match exato de nome completo foi encontrado
        boolean matchExatoEncontrado = false;
        
        // NOVO: Primeiro tenta encontrar nomes completos na query, ignorando prefixos como "Ver." e "Executivo -"
        System.out.println("Verificando nomes completos na query: '" + queryNormalizada + "'");

        // Extrai o nome principal da query para comparação
        String queryLimpa = queryNormalizada.trim();
        
        // Guarda os tokens da query original que correspondem ao nome completo para remoção posterior
        Set<String> tokensNomeCompletoEncontrado = new HashSet<>();
        
        for (String autorCompleto : todosOsAutores) {
            // Remove prefixos e sufixos do nome do autor para comparação
            String nomeLimpo = autorCompleto.replaceAll("\\s*\\(.*\\)", "").trim();
            
            // Remove prefixos comuns para comparação
            String nomeSemPrefixo = nomeLimpo
                .replaceAll("^Ver\\. ", "")
                .replaceAll("^Executivo - ", "")
                .replaceAll("^Dr\\. ", "")
                .trim();
            
            // Normaliza o nome sem prefixo
            String nomeSemPrefixoNormalizado = normalizeText(nomeSemPrefixo);
            
            // Ignora nomes com apenas uma palavra
            if (!nomeSemPrefixoNormalizado.contains(" ")) {
                continue;
            }
            
            // Verifica se a query contém o nome sem prefixo
            if (queryLimpa.equals(nomeSemPrefixoNormalizado) || 
                queryNormalizada.contains(" " + nomeSemPrefixoNormalizado + " ")) {
                
                // Match exato encontrado
                if (!autoresJaAdicionados.contains(nomeLimpo)) {
                    autoresEncontrados.add(nomeLimpo);
                    autoresJaAdicionados.add(nomeLimpo);
                    System.out.println("Match exato de nome completo encontrado: '" + nomeLimpo +
                                      "' (comparando com nome sem prefixo: '" + nomeSemPrefixoNormalizado + "')");
                    
                    // Marca os tokens para remoção
                    String[] tokens = nomeSemPrefixoNormalizado.split("\\s+");
                    for (String token : tokens) {
                        tokensNomeCompletoEncontrado.add(token);
                    }
                    
                    // Como encontramos pelo menos um match exato, marcamos a flag
                    matchExatoEncontrado = true;
                }
                
                // Continuamos o loop para encontrar mais matches exatos (ex: Ver. RICARDO NUNES e Executivo - RICARDO NUNES)
            }
        }
        
        // Se encontramos matches exatos, removemos os tokens correspondentes da query
        if (matchExatoEncontrado) {
            System.out.println("Tokens de nomes completos a serem removidos: " + tokensNomeCompletoEncontrado);
            for (String token : tokensNomeCompletoEncontrado) {
                lowerCaseQuery = lowerCaseQuery.replace(" " + token + " ", " ");
            }
            queryNormalizada = normalizeText(lowerCaseQuery);
        }
        
        // Processamos por tokens individuais se NÃO encontramos matches exatos OU 
        // SE estamos em um caso especial (ex: Hato) onde queremos encontrar mais autores
        if (!matchExatoEncontrado) {
            System.out.println("Nenhum match exato de nome completo encontrado. Processando por tokens individuais.");

            // Primeiro passo: identificar todos os autores sem modificar a query
            for (String autorCompleto : todosOsAutores) {
                String nomeLimpo = autorCompleto.replaceAll("\\s*\\(.*\\)", "").trim();
                
                // Pulamos autores que já foram adicionados via match exato
                if (autoresJaAdicionados.contains(nomeLimpo)) {
                    continue;
                }
                
                String nomeNormalizado = normalizeText(nomeLimpo);
                
                // Verifica se este autor é o Danilo do Posto de Saúde
                boolean isDanilo = nomeNormalizado.contains(DANILO_POSTO_SAUDE);
                
                // Se for o Danilo E a query contém "saude" MAS não parece ser uma busca específica pelo vereador
                if (isDanilo && queryContemSaude && !buscaPeloVereador) {
                    System.out.println("Pulando autor específico: " + nomeLimpo + " (query sobre saúde em geral)");
                    continue; // Pula para o próximo autor
                }
                
                // Processamento normal para outros autores ou para o Danilo quando a busca é por ele
                String[] tokensNome = nomeNormalizado.split("\\s+");
                for (String token : tokensNome) {
                    // Ignoramos stopwords e tokens muito curtos
                    if (STOPWORDS.contains(token) || token.length() <= 2) {
                        continue;
                    }
                    
                    // Ignoramos tokens que são termos temáticos presentes na query
                    if (TERMOS_TEMATICOS.contains(token) && queryNormalizada.contains(" " + token + " ")) {
                        System.out.println("Ignorando token '" + token + "' do autor '" + nomeLimpo +
                                          "' por ser termo temático presente na query");
                        continue;
                    }
                    
                    // Caso especial: para o Danilo, nunca usamos o token "saude" para match a menos que 
                    // seja uma busca específica pelo vereador
                    if (isDanilo && token.equals(TERMO_SAUDE) && !buscaPeloVereador) {
                        continue;
                    }
                    
                    // Verifica se o token está na query normalizada
                    if (queryNormalizada.contains(" " + token + " ")) {
                        // Evita duplicações
                        if (!autoresJaAdicionados.contains(nomeLimpo)) {
                            autoresJaAdicionados.add(nomeLimpo);
                            autoresEncontrados.add(nomeLimpo);
                            System.out.println("Autor encontrado: " + nomeLimpo + " (via token: '" + token + "')");

                            // Marca o token para remoção posterior
                            tokensParaRemover.put(token, true);
                        }
                        
                        // Não remove o token imediatamente para permitir encontrar outros autores
                        // com o mesmo token (ex: sobrenome "Hato" para dois vereadores diferentes)
                        break;
                    }
                }
            }
            
            // Segundo passo: remover todos os tokens identificados da query
            System.out.println("Tokens a serem removidos da query: " + tokensParaRemover.keySet());
            for (String token : tokensParaRemover.keySet()) {
                lowerCaseQuery = lowerCaseQuery.replace(" " + token + " ", " ");
            }
        }
        
        // Atualiza a query normalizada após todas as remoções
        queryNormalizada = normalizeText(lowerCaseQuery);
        System.out.println("Query semântica após remoção de autores: '" + lowerCaseQuery.trim() + "'");

        // 6. Limpa a query semântica final (remove stopwords e normaliza espaços)
        // Lista expandida de stopwords para melhor qualidade da busca semântica
        String semanticQuery = lowerCaseQuery
                .replaceAll("\\s(de|do|da|dos|das|em|no|na|nos|nas|para|pelo|pela|pelos|pelas|" +
                           "a|o|as|os|e|com|quais|que|qual|quem|quando|onde|" +
                           "projetos?|apresentados?|sobre|referente|relativo|acerca|" +
                           "ou|ao|aos|à|às|entre|até|" +
                           "tipo|número|lei|decreto|" +
                           "muito|muitos?|pouco|poucos?)\\s", " ")
                .trim();
                
        // Aplicar filtros de exclusão
        List<String> autoresFiltrados = autoresEncontrados;
        List<Integer> anosFiltrados = anosEncontrados;
        
        // Reincorporar termos excluídos à query semântica
        StringBuilder semanticQueryBuilder = new StringBuilder(semanticQuery);
        
        // Filtrar autores excluídos e reincorporar à query
        if (excludedFilters.containsKey("Autor") && !excludedFilters.get("Autor").isEmpty()) {
            System.out.println("Aplicando exclusão para autores: " + excludedFilters.get("Autor"));

            // Criar lista de autores excluídos
            List<String> autoresExcluidos = autoresEncontrados.stream()
                .filter(autor -> excludedFilters.get("Autor").contains(autor))
                .collect(Collectors.toList());
                
            // Filtrar a lista principal
            autoresFiltrados = autoresEncontrados.stream()
                .filter(autor -> !excludedFilters.get("Autor").contains(autor))
                .collect(Collectors.toList());
                
            // Reincorporar tokens relevantes à query
            for (String autorExcluido : autoresExcluidos) {
                // Extrair sobrenome ou token principal para reincorporar à query
                String[] parts = autorExcluido.split(" ");
                String tokenPrincipal = "";
                
                // Extrair token mais relevante do nome do autor
                if (parts.length > 0) {
                    // Se for "Ver. Nome Sobrenome", pegar o último token (sobrenome)
                    tokenPrincipal = parts[parts.length - 1].toLowerCase();
                    if (tokenPrincipal.length() > 3) {
                        semanticQueryBuilder.append(" ").append(tokenPrincipal);
                        System.out.println("Reincorporando token de autor à query: " + tokenPrincipal);
                    }
                }
            }
        }
        
        // Filtrar anos excluídos e reincorporar à query
        if (excludedFilters.containsKey("Ano") && !excludedFilters.get("Ano").isEmpty()) {
            System.out.println("Aplicando exclusão para anos: " + excludedFilters.get("Ano"));

            // Criar lista de anos excluídos
            List<Integer> anosExcluidos = anosEncontrados.stream()
                .filter(ano -> excludedFilters.get("Ano").contains(ano.toString()))
                .collect(Collectors.toList());
                
            // Filtrar a lista principal
            anosFiltrados = anosEncontrados.stream()
                .filter(ano -> !excludedFilters.get("Ano").contains(ano.toString()))
                .collect(Collectors.toList());
                
            // Reincorporar anos à query
            for (Integer anoExcluido : anosExcluidos) {
                semanticQueryBuilder.append(" ").append(anoExcluido);
                System.out.println("Reincorporando ano à query: " + anoExcluido);
            }
        }
        
        // Filtrar tipo de projeto e reincorporar à query
        String tipoProjetoFiltrado = tipoProjetoEncontrado;
        if (excludedFilters.containsKey("Tipo") && !excludedFilters.get("Tipo").isEmpty() 
            && tipoProjetoEncontrado != null && excludedFilters.get("Tipo").contains(tipoProjetoEncontrado)) {
            System.out.println("Aplicando exclusão para tipo: " + tipoProjetoEncontrado);
            tipoProjetoFiltrado = null;
            
            // Reincorporar o tipo à query
            semanticQueryBuilder.append(" ").append(tipoProjetoEncontrado.toLowerCase());
            System.out.println("Reincorporando tipo à query: " + tipoProjetoEncontrado);
        }
        
        // Filtrar número do projeto e reincorporar à query
        Integer numeroProjetoFiltrado = numeroProjetoEncontrado;
        if (excludedFilters.containsKey("Número") && !excludedFilters.get("Número").isEmpty()
            && numeroProjetoEncontrado != null && excludedFilters.get("Número").contains(numeroProjetoEncontrado.toString())) {
            System.out.println("Aplicando exclusão para número: " + numeroProjetoEncontrado);
            numeroProjetoFiltrado = null;
            
            // Reincorporar o número à query
            semanticQueryBuilder.append(" ").append(numeroProjetoEncontrado);
            System.out.println("Reincorporando número à query: " + numeroProjetoEncontrado);
        }
        
        // Atualizar a query semântica com os termos reincorporados
        String finalSemanticQuery = semanticQueryBuilder.toString().trim().replaceAll("\\s+", " ");
        System.out.println("Query semântica final após reincorporação: '" + finalSemanticQuery + "'");

        return new SearchFilter(autoresFiltrados, anosFiltrados, numeroProjetoFiltrado, tipoProjetoFiltrado, 
                               finalSemanticQuery, exactPhrases);
    }

    // Classe auxiliar para armazenar os filtros extraídos
    @lombok.Getter
    @lombok.ToString
    private static class SearchFilter {
        private final List<String> autores;
        private final List<Integer> anos;
        private final String semanticQuery;
        private final Integer numeroProjeto;
        private final String tipoProjeto;
        private final List<String> exactPhrases; // Novo campo para termos exatos

        SearchFilter(List<String> autores, List<Integer> anos, Integer numeroProjeto, String tipoProjeto, String semanticQuery) {
            this.autores = autores;
            this.anos = anos;
            this.numeroProjeto = numeroProjeto;
            this.tipoProjeto = tipoProjeto;
            this.semanticQuery = semanticQuery;
            this.exactPhrases = new ArrayList<>(); // Lista vazia por padrão para manter compatibilidade
        }

        SearchFilter(List<String> autores, List<Integer> anos, Integer numeroProjeto, String tipoProjeto, String semanticQuery, List<String> exactPhrases) {
            this.autores = autores;
            this.anos = anos;
            this.numeroProjeto = numeroProjeto;
            this.tipoProjeto = tipoProjeto;
            this.semanticQuery = semanticQuery;
            this.exactPhrases = exactPhrases;
        }
    }

    private Map<String, List<String>> buildAppliedFiltersMap(SearchFilter filter) {
        Map<String, List<String>> appliedFilters = new HashMap<>();
        if (filter.getAutores() != null && !filter.getAutores().isEmpty()) {
            List<String> autoresDisplay = filter.getAutores().stream()
                    .map(a -> a.replace("(", "").replace(")", ""))
                    .collect(Collectors.toList());
            appliedFilters.put("Autor", autoresDisplay);
        }
        if (filter.getAnos() != null && !filter.getAnos().isEmpty()) {
            appliedFilters.put("Ano", filter.getAnos().stream().map(String::valueOf).collect(Collectors.toList()));
        }
        if (filter.getTipoProjeto() != null) {
            appliedFilters.put("Tipo", List.of(filter.getTipoProjeto()));
        }
        if (filter.getNumeroProjeto() != null) {
            appliedFilters.put("Número", List.of(String.valueOf(filter.getNumeroProjeto())));
        }
        return appliedFilters;
    }
}