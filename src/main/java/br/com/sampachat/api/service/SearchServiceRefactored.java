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
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Year;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class SearchServiceRefactored {

    private static final Logger logger = LoggerFactory.getLogger(SearchServiceRefactored.class);

    //<editor-fold desc="Injeção de Dependências e Constantes">
    @Autowired private EmbeddingService embeddingService;
    @Autowired private ProjetoRepository projetoRepository;
    @Autowired private LinkBuilderService linkBuilderService;
    @PersistenceContext private EntityManager entityManager;

    private List<String> todosOsAutores;
    private Set<String> todosOsPartidos;

    // Constantes para extração de filtros
    private static final Pattern QUOTE_PATTERN = Pattern.compile("\"([^\"]*)\"");
    private static final Map<String, String> TIPOS_PROJETO_MAP = createTiposProjetoMap();
    private static final Pattern TIPO_NUMERO_PATTERN = createTipoNumeroPattern();
    private static final Pattern TIPO_PATTERN = createTipoPattern();
    private static final Pattern INTERVALO_ANOS_PATTERN = Pattern.compile("(?:entre(?: os anos(?: de)?)? (\\d{4}) e (\\d{4})|de (\\d{4}) a (\\d{4})|(\\d{4}) a (\\d{4})|(\\d{4})-(\\d{4}))");
    private static final Pattern ANO_PATTERN = Pattern.compile("\\b((?:199|20[0-2])\\d)\\b");
    private static final Pattern ANOS_RECENTES_PATTERN = Pattern.compile("últimos (\\d+) anos");
    private static final Pattern NUMERO_PATTERN = Pattern.compile("\\b(\\d{1,4})\\b");

    // Constantes para lógica de negócio
    private static final Set<String> STOPWORDS = Set.of(
            // Preposições atuais
            "de", "da", "do", "dos", "das",

            // Artigos
            "o", "a", "os", "as", "um", "uma", "uns", "umas",

            // Preposições adicionais
            "em", "na", "no", "nas", "nos", "para", "por", "com", "sem", "até", "sobre", "sob", "entre", "após", "perante",

            // Conjunções
            "e", "ou", "mas", "porém", "contudo", "todavia", "entretanto", "que", "porque", "pois", "como", "quando", "se",

            // Pronomes comuns
            "eu", "tu", "ele", "ela", "nós", "vós", "eles", "elas", "este", "esta", "isto", "esse", "essa", "isso",
            "aquele", "aquela", "aquilo", "meu", "minha", "seu", "sua", "nosso", "nossa", "quem", "qual", "cujo", "cuja",

            // Advérbios frequentes
            "não", "sim", "talvez", "muito", "pouco", "mais", "menos", "tão", "apenas", "só", "somente", "já", "ainda",

            // Verbos auxiliares comuns (formas conjugadas principais)
            "é", "são", "foi", "foram", "será", "serão", "tem", "têm", "tinha", "tinham", "há", "haver", "estar", "sendo",

            // Termos específicos de documentos legislativos que ocorrem com alta frequência
            "estabelece"
    );
    private static final Set<String> TERMOS_TEMATICOS = Set.of(
        "saude", "educacao", "mulher", "trabalho", "ambiente", "cultura", "transporte", "direitos", "idoso",
        "crianca", "adolescente", "familia", "habitacao", "moradia", "urbanismo", "seguranca", "comercio",
        "empresarial", "desenvolvimento", "orçamento", "financas", "social", "promocao", "inclusao", "assistencia",
        "politica", "publica", "servico", "verde", "azul", "esporte", "juventude", "cidadania", "consumidor",
        "defesa", "justica", "diversidade", "igualdade", "racial", "genero", "acessibilidade", "mobilidade",
        "transparencia", "participacao", "popular", "tecnologia", "inovacao", "economia", "planejamento",
        "emergencia", "urgencia", "posto", "hospital", "acidente", "animal", "pet", "comunidade", "comissao",
        "comissoes", "urbana", "metropolitana", "meio", "extraordinaria", "relacoes", "internacionais", "administracao",
        "transito", "atividade", "economica", "constituicao", "legislacao", "participativa", "legislativa", "legais",
        "legal", "fiscalizacao", "investigacao", "processante", "parlamentar", "inquerito", "sustentabilidade",
        "tributos", "finanças", "gestao", "patrimonio", "infancia", "fomento", "direito", "projeto", "humanos"
    );
    //</editor-fold>

    @PostConstruct
    public void init() {
        todosOsAutores = projetoRepository.findDistinctAutores();
        
        Pattern partidoPattern = Pattern.compile("\\((.*?)\\)");
        todosOsPartidos = todosOsAutores.stream()
            .map(partidoPattern::matcher)
            .filter(Matcher::find)
            .map(matcher -> matcher.group(1).toLowerCase())
            .collect(Collectors.toSet());
        
    }

    /**
     * Realiza uma busca híbrida (semântica + por filtros) de forma paginada.
     */
    public HybridSearchResultDTO searchHybridPaged(String userQuery, int page, int size, Map<String, List<String>> excludedFilters) {
        try {
            logger.info("Iniciando busca híbrida. Query: '{}', Page: {}, Size: {}, Exclusions: {}", userQuery, page, size, excludedFilters);
            

            // 1. Extrai filtros (autor, ano, etc.) e a query semântica da busca do usuário.
            SearchFilter filter = extractFilters(userQuery, excludedFilters);
            logger.info("Filtros extraídos: {}", filter);

            // 2. Busca IDs de projetos que correspondem aos filtros.
            List<Integer> relevantIds = findRelevantIdsByFilters(filter);
            if (relevantIds.isEmpty()) {
                logger.info("Nenhum projeto encontrado para os filtros aplicados.");
                return createEmptyResult(filter, page, size);
            }
            logger.info("{} projetos encontrados após filtragem inicial.", relevantIds.size());

            // 3. Se não há query semântica, retorna apenas os resultados filtrados e paginados.
            if (filter.getSemanticQuery().isBlank()) {
                return performFilterOnlySearch(relevantIds, userQuery, filter, page, size);
            }

            // 4. Realiza a busca híbrida (exata + semântica) e retorna os resultados.
            return performHybridSearch(relevantIds, userQuery, filter, page, size);

        } catch (Exception e) {
            logger.error("Erro fatal durante a busca híbrida para a query: '{}'", userQuery, e);
            return createEmptyResult(new SearchFilter(new ArrayList<>(), new ArrayList<>(), null, null, "", new ArrayList<>()), page, size);
        }
    }

    //<editor-fold desc="Lógica Principal da Busca">

    /**
     * Encontra IDs de projetos que correspondem aos filtros de metadados.
     */
    private List<Integer> findRelevantIdsByFilters(SearchFilter filter) {
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
        if (!filter.getAnos().isEmpty()) {
            jpql.append(" AND p.ano IN :anos");
            parameters.put("anos", filter.getAnos());
        }
        if (!filter.getAutores().isEmpty()) {
            List<String> autoresUnicos = filter.getAutores().stream().distinct().toList();
            jpql.append(" AND (");
            for (int i = 0; i < autoresUnicos.size(); i++) {
                String paramName = "autor" + i;
                if (i > 0) jpql.append(" OR ");
                jpql.append("LOWER(p.autor) LIKE LOWER(:").append(paramName).append(")");
                parameters.put(paramName, "%" + autoresUnicos.get(i) + "%");
            }
            jpql.append(")");
        }
        
        TypedQuery<Integer> idQuery = entityManager.createQuery(jpql.toString(), Integer.class);
        parameters.forEach(idQuery::setParameter);
        return idQuery.getResultList();
    }

    /**
     * Realiza uma busca paginada quando apenas filtros foram aplicados (sem query semântica).
     */
    private HybridSearchResultDTO performFilterOnlySearch(List<Integer> relevantIds, String originalQuery, SearchFilter filter, int page, int size) {
        logger.info("Executando busca apenas por filtros.");

        String countJpql = "SELECT COUNT(p) FROM Projeto p WHERE p.id IN (:ids)";
        TypedQuery<Long> countQuery = entityManager.createQuery(countJpql, Long.class);
        countQuery.setParameter("ids", relevantIds);
        long total = countQuery.getSingleResult();

        String finalJpql = "SELECT p FROM Projeto p WHERE p.id IN (:ids) ORDER BY p.ano DESC, p.numero DESC";
        TypedQuery<Projeto> finalFilterQuery = entityManager.createQuery(finalJpql, Projeto.class);
        finalFilterQuery.setParameter("ids", relevantIds);
        finalFilterQuery.setFirstResult(page * size);
        finalFilterQuery.setMaxResults(size);
        List<Projeto> projetosFiltrados = finalFilterQuery.getResultList();

        List<String> termsForHighlight = getTermsForHighlight(originalQuery, filter.getExactPhrases());
        List<ProjetoResponseDTO> dtos = convertToDto(projetosFiltrados);
        
        return new HybridSearchResultDTO(dtos, buildAppliedFiltersMap(filter), page, size, (int) total, termsForHighlight);
    }

    /**
     * Executa a busca híbrida combinando resultados de match exato e busca semântica.
     */
    private HybridSearchResultDTO performHybridSearch(List<Integer> relevantIds, String originalQuery, SearchFilter filter, int page, int size) {
        logger.info("Executando busca híbrida com query semântica: '{}'", filter.getSemanticQuery());

        // Prepara os termos da query para busca e ranking
        List<String> queryTerms = getQueryTermsForSearch(filter);
        
        // 1. Busca por correspondências exatas dos termos da query.
        List<Projeto> exactMatches = findExactMatches(relevantIds, queryTerms);
        logger.info("Encontrados {} matches exatos.", exactMatches.size());

        // 2. Se necessário, complementa com busca semântica.
        List<Projeto> semanticMatches = new ArrayList<>();
        if (exactMatches.size() < AppConstants.MAX_RESULTS_LIMIT) {
            List<Integer> exactMatchIds = exactMatches.stream().map(Projeto::getId).toList();
            int limit = AppConstants.MAX_RESULTS_LIMIT - exactMatches.size();
            semanticMatches = findSemanticMatches(relevantIds, filter.getSemanticQuery(), exactMatchIds, limit);
            logger.info("Encontrados {} matches semânticos.", semanticMatches.size());
        }

        // 3. Combina e ordena os resultados (exatos primeiro, depois semânticos, ambos por relevância e data).
        List<Projeto> finalRankedResults = combineAndRankResults(exactMatches, semanticMatches, queryTerms);

        // 4. Aplica o filtro de "frases exatas" (termos entre aspas).
        List<Projeto> finalFilteredResults = applyExactPhraseFilter(finalRankedResults, filter.getExactPhrases());
        logger.info("Total de resultados após filtro de frases exatas: {}", finalFilteredResults.size());

        // 5. Pagina os resultados finais.
        List<Projeto> pagedResults = paginateResults(finalFilteredResults, page, size);
        
        // 6. Prepara o DTO de resposta.
        List<String> termsForHighlight = getTermsForHighlight(originalQuery, filter.getExactPhrases());
        List<ProjetoResponseDTO> dtos = convertToDto(pagedResults);

        return new HybridSearchResultDTO(dtos, buildAppliedFiltersMap(filter), page, size, finalFilteredResults.size(), termsForHighlight);
    }

    //</editor-fold>

    //<editor-fold desc="Passos da Busca Híbrida">

    private List<Projeto> findExactMatches(List<Integer> relevantIds, List<String> queryTerms) {
        if (queryTerms.isEmpty()) return Collections.emptyList();
    
        StringBuilder sql = new StringBuilder("SELECT * FROM projetos WHERE id IN (:ids) AND (");
        for (int i = 0; i < queryTerms.size(); i++) {
            if (i > 0) sql.append(" OR ");
            String termParam = "term" + i;
            // Removida a função unaccent que estava causando o erro
            sql.append("LOWER(ementa || ' ' || palavras_chave || ' ' || autor) LIKE LOWER(:").append(termParam).append(")");
        }
        sql.append(") ORDER BY ano DESC, numero DESC");

        Query exactMatchQuery = entityManager.createNativeQuery(sql.toString(), Projeto.class);
        exactMatchQuery.setParameter("ids", relevantIds);
        for (int i = 0; i < queryTerms.size(); i++) {
            exactMatchQuery.setParameter("term" + i, "%" + queryTerms.get(i) + "%");
        }
        
        @SuppressWarnings("unchecked")
        List<Projeto> results = exactMatchQuery.getResultList();

        // Validação adicional para remover falsos positivos do LIKE
        return results.stream()
            .filter(projeto -> {
                String textoNormalizado = normalizeText(
                    (projeto.getEmenta() != null ? projeto.getEmenta() : "") + " " +
                    (projeto.getPalavrasChave() != null ? projeto.getPalavrasChave().replace("|", " ") : "") + " " +
                    (projeto.getAutor() != null ? projeto.getAutor() : "")
                );
                return queryTerms.stream().anyMatch(textoNormalizado::contains);
            })
            .toList();
    }

    private List<Projeto> findSemanticMatches(List<Integer> relevantIds, String semanticQuery, List<Integer> idsToExclude, int limit) {
        if (limit <= 0) return Collections.emptyList();

        try {
            float[] queryEmbedding = embeddingService.generateEmbeddings(List.of(semanticQuery))[0];
            String vectorString = Arrays.toString(queryEmbedding);

            StringBuilder sql = new StringBuilder("SELECT * FROM projetos WHERE id IN (:ids) ");
            if (!idsToExclude.isEmpty()) {
                sql.append("AND id NOT IN (:excludeIds) ");
            }
            sql.append("ORDER BY embedding <=> CAST(:queryVector AS vector) LIMIT :limit");

            Query semanticQueryObj = entityManager.createNativeQuery(sql.toString(), Projeto.class);
            semanticQueryObj.setParameter("ids", relevantIds);
            semanticQueryObj.setParameter("queryVector", vectorString);
            semanticQueryObj.setParameter("limit", limit);
            if (!idsToExclude.isEmpty()) {
                semanticQueryObj.setParameter("excludeIds", idsToExclude);
            }

            @SuppressWarnings("unchecked")
            List<Projeto> results = semanticQueryObj.getResultList();
            return results;
        } catch (Exception e) {
            logger.error("Erro ao buscar matches semânticos: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }
    
    private List<Projeto> combineAndRankResults(List<Projeto> exactMatches, List<Projeto> semanticMatches, List<String> queryTerms) {
        List<Projeto> combined = new ArrayList<>(exactMatches);
        combined.addAll(semanticMatches);

        // Mapa para armazenar quantos termos únicos da query estão presentes em cada projeto
        Map<Integer, Long> uniqueTermsCount = combined.stream()
            .collect(Collectors.toMap(
                Projeto::getId,
                projeto -> {
                    String textoNormalizado = normalizeText(
                        (projeto.getEmenta() != null ? projeto.getEmenta() : "") + " " +
                        (projeto.getPalavrasChave() != null ? projeto.getPalavrasChave().replace("|", " ") : "")
                    );
                    return queryTerms.stream().filter(textoNormalizado::contains).count();
                }
            ));
            
        // Novo mapa para armazenar o número total de ocorrências de termos da query em cada projeto
        Map<Integer, Integer> totalOccurrencesCount = combined.stream()
            .collect(Collectors.toMap(
                Projeto::getId,
                projeto -> {
                    String textoNormalizado = normalizeText(
                        (projeto.getEmenta() != null ? projeto.getEmenta() : "") + " " +
                        (projeto.getPalavrasChave() != null ? projeto.getPalavrasChave().replace("|", " ") : "")
                    );
                    
                    // Conta o número total de aparições de todos os termos da query
                    int totalOccurrences = 0;
                    for (String term : queryTerms) {
                        // Conta quantas vezes o termo aparece no texto
                        int count = 0;
                        int lastIndex = 0;
                        
                        while ((lastIndex = textoNormalizado.indexOf(term, lastIndex)) != -1) {
                            count++;
                            lastIndex += term.length();
                        }
                        
                        totalOccurrences += count;
                    }
                    
                    return totalOccurrences;
                }
            ));

        combined.sort((p1, p2) -> {
            boolean p1IsExact = exactMatches.contains(p1);
            boolean p2IsExact = exactMatches.contains(p2);

            // 1. Match exato tem prioridade sobre match semântico
            if (p1IsExact && !p2IsExact) return -1;
            if (!p1IsExact && p2IsExact) return 1;

            // 2. Quantidade de termos únicos da query presentes
            int compareByUniqueTerms = Long.compare(
                uniqueTermsCount.getOrDefault(p2.getId(), 0L), 
                uniqueTermsCount.getOrDefault(p1.getId(), 0L)
            );
            if (compareByUniqueTerms != 0) return compareByUniqueTerms;
            
            // 3. NOVO: Quantidade total de ocorrências dos termos
            int compareByTotalOccurrences = Integer.compare(
                totalOccurrencesCount.getOrDefault(p2.getId(), 0), 
                totalOccurrencesCount.getOrDefault(p1.getId(), 0)
            );
            if (compareByTotalOccurrences != 0) return compareByTotalOccurrences;

            // 4. Projetos mais recentes têm prioridade
            int compareByAno = Integer.compare(p2.getAno(), p1.getAno());
            if (compareByAno != 0) return compareByAno;

            // 5. Projetos com número maior têm prioridade
            return Integer.compare(p2.getNumero(), p1.getNumero());
        });

        return combined;
    }
    
    private List<Projeto> applyExactPhraseFilter(List<Projeto> projects, List<String> exactPhrases) {
        if (exactPhrases.isEmpty()) {
            return projects;
        }
        
        List<String> normalizedPhrases = exactPhrases.stream().map(this::normalizeText).toList();
        
        return projects.stream()
            .filter(projeto -> {
                String textoCompleto = normalizeText(
                    String.join(" ", 
                        Optional.ofNullable(projeto.getEmenta()).orElse(""),
                        Optional.ofNullable(projeto.getPalavrasChave()).orElse("").replace("|", " "),
                        Optional.ofNullable(projeto.getAutor()).orElse(""),
                        String.valueOf(projeto.getNumero()),
                        String.valueOf(projeto.getAno()),
                        Optional.ofNullable(projeto.getTipo()).map(Enum::toString).orElse("")
                    )
                );
                return normalizedPhrases.stream().allMatch(textoCompleto::contains);
            })
            .toList();
    }
    
    private List<Projeto> paginateResults(List<Projeto> results, int page, int size) {
        int total = results.size();
        int start = page * size;
        if (start >= total) {
            return Collections.emptyList();
        }
        int end = Math.min(start + size, total);
        return results.subList(start, end);
    }

    //</editor-fold>

    //<editor-fold desc="Extração de Filtros (Refatorado)">

    private SearchFilter extractFilters(String query, Map<String, List<String>> excludedFilters) {
        if (query == null || query.trim().length() <= 1) {
            return new SearchFilter(new ArrayList<>(), new ArrayList<>(), null, null, query != null ? query.trim() : "", new ArrayList<>());
        }
    
        // 1. Extrai frases exatas (entre aspas)
        Matcher quoteMatcher = QUOTE_PATTERN.matcher(query);
        List<String> exactPhrases = new ArrayList<>();
        while (quoteMatcher.find()) {
            exactPhrases.add(quoteMatcher.group(1).trim());
        }
        String querySemAspas = query.replaceAll("\"", " ");
    
        // 2. Extrai filtros estruturados (tipo, número, ano, autor)
        FilterExtractionResult extractionResult = extractStructuredFilters(querySemAspas);
    
        // 3. Aplica exclusões e reincorpora termos à query semântica
        String finalSemanticQuery = applyExclusionsAndReincorporateTerms(extractionResult, excludedFilters);
    
        return new SearchFilter(
            new ArrayList<>(extractionResult.autores),
            extractionResult.anos,
            extractionResult.numero,
            extractionResult.tipo,
            finalSemanticQuery,
            exactPhrases
        );
    }
    
    private FilterExtractionResult extractStructuredFilters(String query) {
        String remainingQuery = " " + query.toLowerCase().replaceAll("\\s-\\s", " ") + " ";
        FilterExtractionResult result = new FilterExtractionResult();
    
        // Ordem de extração é importante para evitar ambiguidades
        remainingQuery = extractTipoAndNumero(remainingQuery, result);
        
        // Se não encontrou um tipo com número, tenta extrair apenas o tipo
        if (result.tipo == null) {
            remainingQuery = extractTipo(remainingQuery, result);
        }
        
        remainingQuery = extractAnos(remainingQuery, result);
        remainingQuery = extractNumero(remainingQuery, result);
        remainingQuery = extractAutoresAndPartidos(remainingQuery, result);
    
        result.semanticQuery = remainingQuery.trim().replaceAll("\\s+", " ");
        return result;
    }

    private String extractTipoAndNumero(String query, FilterExtractionResult result) {
        Matcher matcher = TIPO_NUMERO_PATTERN.matcher(query);
        if (matcher.find()) {
            result.tipo = TIPOS_PROJETO_MAP.get(matcher.group(1).toLowerCase());
            result.numero = Integer.parseInt(matcher.group(2));
            
            // Verificar se o grupo 3 (ano) foi capturado
            if (matcher.groupCount() >= 3 && matcher.group(3) != null) {
                result.anos.add(Integer.parseInt(matcher.group(3)));
            }
            
            return query.replace(matcher.group(0), " ");
        }
        return query;
    }
    
    private String extractTipo(String query, FilterExtractionResult result) {
        Matcher matcher = TIPO_PATTERN.matcher(query);
        if (matcher.find()) {
            String tipoEncontrado = matcher.group(1).toLowerCase();
            result.tipo = TIPOS_PROJETO_MAP.get(tipoEncontrado);
            logger.debug("Tipo de projeto encontrado: '{}' -> {}", tipoEncontrado, result.tipo);
            return query.replace(matcher.group(0), " ");
        }
        return query;
    }

    private String extractAnos(String query, FilterExtractionResult result) {
        // Intervalos primeiro
        Matcher intervaloMatcher = INTERVALO_ANOS_PATTERN.matcher(query);
        if (intervaloMatcher.find()) {
            int anoInicio = Integer.parseInt(Optional.ofNullable(intervaloMatcher.group(1)).orElseGet(() -> Optional.ofNullable(intervaloMatcher.group(3)).orElseGet(() -> Optional.ofNullable(intervaloMatcher.group(5)).orElse(intervaloMatcher.group(7)))));
            int anoFim = Integer.parseInt(Optional.ofNullable(intervaloMatcher.group(2)).orElseGet(() -> Optional.ofNullable(intervaloMatcher.group(4)).orElseGet(() -> Optional.ofNullable(intervaloMatcher.group(6)).orElse(intervaloMatcher.group(8)))));
            for (int ano = Math.min(anoInicio, anoFim); ano <= Math.max(anoInicio, anoFim); ano++) {
                result.anos.add(ano);
            }
            return query.replace(intervaloMatcher.group(0), " ");
        }

        // Anos individuais
        String modifiableQuery = query;
        Matcher anoMatcher = ANO_PATTERN.matcher(modifiableQuery);
        while (anoMatcher.find()) {
            result.anos.add(Integer.parseInt(anoMatcher.group(1)));
        }
        modifiableQuery = anoMatcher.replaceAll(" ");

        // "Últimos X anos"
        Matcher recentesMatcher = ANOS_RECENTES_PATTERN.matcher(modifiableQuery);
        if (recentesMatcher.find()) {
            int numAnos = Integer.parseInt(recentesMatcher.group(1));
            int anoAtual = Year.now().getValue();
            for (int i = 0; i < numAnos; i++) {
                result.anos.add(anoAtual - i);
            }
            return recentesMatcher.replaceAll(" ");
        }
        
        return modifiableQuery;
    }

    private String extractNumero(String query, FilterExtractionResult result) {
        if (result.numero != null) return query; // Já extraído com o tipo
        Matcher matcher = NUMERO_PATTERN.matcher(query);
        if (matcher.find()) {
            int num = Integer.parseInt(matcher.group(1));
            if (num > 0 && num <= 1500) {
                result.numero = num;
                return query.replaceFirst("\\b" + matcher.group(1) + "\\b", " ");
            }
        }
        return query;
    }

    private String extractAutoresAndPartidos(String query, FilterExtractionResult result) {
        String queryNormalizada = normalizeText(query);
        String modifiableQuery = query;

        // 1. Quebra a query do usuário em palavras (tokens) para análise.
        // Ignoramos palavras muito curtas ou stopwords.
        List<String> queryTokens = Arrays.stream(queryNormalizada.split("\\s+"))
                .filter(token -> token.length() > 2 && !STOPWORDS.contains(token))
                .distinct()
                .toList();

        if (queryTokens.isEmpty()) {
            return query; // Retorna a query original se não houver tokens válidos.
        }

        // Conjunto para armazenar os tokens da query que foram identificados como parte de um nome de autor.
        Set<String> tokensParaRemover = new HashSet<>();

        // 2. Itera sobre cada autor conhecido no sistema.
        for (String autorCompleto : todosOsAutores) {
            // Limpa o nome do autor, removendo o partido. Ex: "Keit Lima (PODE)" -> "Keit Lima"
            String nomeLimpo = autorCompleto.replaceAll("\\s*\\(.*\\)", "").trim();
            String nomeNormalizado = normalizeText(nomeLimpo);

            // Quebra o nome do autor em palavras. Ex: "keit lima" -> {"keit", "lima"}
            Set<String> autorTokens = new HashSet<>(Arrays.asList(nomeNormalizado.split("\\s+")));

            // 3. Compara os tokens da query com os tokens do nome do autor.
            for (String queryToken : queryTokens) {
                // A MÁGICA ACONTECE AQUI:
                // Verifica se uma palavra da query corresponde EXATAMENTE a uma palavra do nome do autor.
                // Ex: queryToken "keit" está contido em autorTokens {"keit", "lima"} -> true
                // Ex: queryToken "keit" está contido em autorTokens {"keity", "souza"} -> false
                if (autorTokens.contains(queryToken)) {
                    // Se encontrou uma correspondência, adiciona o autor aos filtros.
                    result.autores.add(nomeLimpo);
                    // E marca o token da query para ser removido da busca semântica.
                    tokensParaRemover.add(queryToken);
                    // Otimização: Uma vez que encontramos uma correspondência para este autor,
                    // podemos parar de verificar outros tokens da query para ele.
                    break;
                }
            }
        }

        // 4. Remove da query original os tokens que foram identificados como nomes de autores.
        for (String token : tokensParaRemover) {
            // Usamos "\\b" (word boundary) para garantir que estamos substituindo a palavra inteira,
            // e não parte de outra palavra. (?i) faz a substituição ser case-insensitive.
            modifiableQuery = modifiableQuery.replaceAll("(?i)\\b" + Pattern.quote(token) + "\\b", " ");
        }

        logger.debug("Autores encontrados: {}", result.autores);
        logger.debug("Query após extração de autores: '{}'", modifiableQuery);

        // Limpa espaços duplicados e retorna a query pronta para a busca semântica.
        return modifiableQuery.trim().replaceAll("\\s+", " ");
    }

    private String applyExclusionsAndReincorporateTerms(FilterExtractionResult extracted, Map<String, List<String>> excludedFilters) {
        StringBuilder semanticQueryBuilder = new StringBuilder(extracted.semanticQuery);
    
        // Reincorpora termos de filtros excluídos para que façam parte da busca semântica
        excludedFilters.forEach((key, values) -> {
            switch (key) {
                case "Autor":
                    extracted.autores.removeIf(autor -> {
                        if (values.contains(autor)) {
                            // Pega o sobrenome ou parte principal para a busca
                            String[] parts = autor.split(" ");
                            if (parts.length > 1) semanticQueryBuilder.append(" ").append(parts[parts.length-1]);
                            return true;
                        }
                        return false;
                    });
                    break;
                case "Ano":
                    extracted.anos.removeIf(ano -> {
                        if (values.contains(String.valueOf(ano))) {
                            semanticQueryBuilder.append(" ").append(ano);
                            return true;
                        }
                        return false;
                    });
                    break;
                case "Tipo":
                    if (values.contains(extracted.tipo)) {
                        semanticQueryBuilder.append(" ").append(extracted.tipo);
                        extracted.tipo = null;
                    }
                    break;
                case "Número":
                    if (values.contains(String.valueOf(extracted.numero))) {
                        semanticQueryBuilder.append(" ").append(extracted.numero);
                        extracted.numero = null;
                    }
                    break;
            }
        });
        
        return semanticQueryBuilder.toString().trim().replaceAll("\\s+", " ");
    }
    //</editor-fold>

    //<editor-fold desc="Utilitários e Helpers">

    private String normalizeText(String texto) {
        if (texto == null || texto.isBlank()) return "";
        return Normalizer.normalize(texto.toLowerCase(), Normalizer.Form.NFD)
                         .replaceAll("\\p{M}", "");
    }

    private List<ProjetoResponseDTO> convertToDto(List<Projeto> projetos) {
        return projetos.stream()
            .map(p -> new ProjetoResponseDTO(
                p,
                linkBuilderService.buildSpLegisLink(p.getTipo(), p.getNumero(), p.getAno()),
                linkBuilderService.buildPortalLink(p.getTipo(), p.getNumero(), p.getAno()),
                linkBuilderService.buildPdfLink(p.getTipo(), p.getNumero(), p.getAno())
            ))
            .toList();
    }

    private List<String> getTermsForHighlight(String originalQuery, List<String> exactPhrases) {
        Set<String> terms = new HashSet<>(exactPhrases);
        terms.addAll(Arrays.asList(originalQuery.trim().split("\\s+")));
        // Filtrar as stopwords antes de retornar os termos
        return terms.stream()
                .filter(t -> t.length() > 1)
                .filter(t -> !STOPWORDS.contains(t.toLowerCase()))  // Adiciona filtro para excluir stopwords
                .distinct()
                .toList();
    }

    private List<String> getQueryTermsForSearch(SearchFilter filter) {
        List<String> terms = new ArrayList<>(
                Arrays.stream(filter.getSemanticQuery().trim().split("\\s+"))
                        .filter(term -> term.length() > 1)
                        .filter(term -> !STOPWORDS.contains(normalizeText(term)))
                        .map(this::normalizeText)
                        .toList()
        );
        filter.getExactPhrases().forEach(phrase -> {
            String normalized = normalizeText(phrase);
            if (!terms.contains(normalized)) terms.add(normalized);
            for (String word : normalized.split("\\s+")) {
                if (word.length() > 1 && !terms.contains(word)) {
                    terms.add(word);
                }
            }
        });
        return terms;
    }

    private HybridSearchResultDTO createEmptyResult(SearchFilter filter, int page, int size) {
        return new HybridSearchResultDTO(Collections.emptyList(), buildAppliedFiltersMap(filter), page, size, 0, filter.getExactPhrases());
    }

    private Map<String, List<String>> buildAppliedFiltersMap(SearchFilter filter) {
        Map<String, List<String>> appliedFilters = new LinkedHashMap<>();
        if (filter.getAutores() != null && !filter.getAutores().isEmpty()) {
            appliedFilters.put("Autor", filter.getAutores().stream().distinct().toList());
        }
        if (filter.getAnos() != null && !filter.getAnos().isEmpty()) {
            appliedFilters.put("Ano", filter.getAnos().stream().map(String::valueOf).distinct().toList());
        }
        if (filter.getTipoProjeto() != null) {
            appliedFilters.put("Tipo", List.of(filter.getTipoProjeto()));
        }
        if (filter.getNumeroProjeto() != null) {
            appliedFilters.put("Número", List.of(String.valueOf(filter.getNumeroProjeto())));
        }
        return appliedFilters;
    }

    /**
     * @deprecated Use searchHybridPaged() instead
     */
    @Deprecated
    public HybridSearchResultDTO searchHybrid(String userQuery) {
        return searchHybridPaged(userQuery, 0, AppConstants.DEFAULT_PAGE_SIZE);
    }

    /**
     * Versão do método mantida para compatibilidade
     */
    public HybridSearchResultDTO searchHybridPaged(String userQuery, int page, int size) {
        return searchHybridPaged(userQuery, page, size, new HashMap<>());
    }

    // Estruturas de dados auxiliares
    @Getter @AllArgsConstructor @ToString
    private static class SearchFilter {
        private final List<String> autores;
        private final List<Integer> anos;
        private final Integer numeroProjeto;
        private final String tipoProjeto;
        private final String semanticQuery;
        private final List<String> exactPhrases;
    }
    
    private static class FilterExtractionResult {
        Set<String> autores = new HashSet<>();
        List<Integer> anos = new ArrayList<>();
        Integer numero;
        String tipo;
        String semanticQuery = "";
    }

    // Métodos de inicialização de constantes
    private static Map<String, String> createTiposProjetoMap() {
        Map<String, String> map = new HashMap<>();
        map.put("pl", "PL"); 
        map.put("projeto de lei", "PL");
        map.put("projetos de lei", "PL");
        map.put("pls", "PL");
        
        map.put("pdl", "PDL"); 
        map.put("projeto de decreto legislativo", "PDL");
        map.put("projetos de decreto legislativo", "PDL");
        map.put("pdls", "PDL");
        
        map.put("plo", "PLO"); 
        map.put("projeto de lei orgânica", "PLO");
        map.put("projeto de lei organica", "PLO");
        map.put("projetos de lei orgânica", "PLO");
        map.put("projetos de lei organica", "PLO");
        map.put("plos", "PLO");
        
        map.put("pr", "PR"); 
        map.put("projeto de resolução", "PR");
        map.put("projeto de resolucao", "PR");
        map.put("projetos de resolução", "PR");
        map.put("projetos de resolucao", "PR");
        map.put("prs", "PR");
        
        return map;
    }
    
    private static Pattern createTipoNumeroPattern() {
        String tiposKeys = String.join("|", createTiposProjetoMap().keySet());
        return Pattern.compile("\\b(" + tiposKeys + ")\\s*(\\d{1,4})(?:\\s*[/\\s]\\s*(\\d{4}))?\\b", Pattern.CASE_INSENSITIVE);
    }
    
    private static Pattern createTipoPattern() {
        String tiposKeys = String.join("|", createTiposProjetoMap().keySet());
        return Pattern.compile("\\b(" + tiposKeys + ")\\b", Pattern.CASE_INSENSITIVE);
    }
    //</editor-fold>
}
