package br.com.sampachat.api.service;

import br.com.sampachat.api.dto.splegis.SPLegisProjetoDTO;
import br.com.sampachat.api.dto.splegis.SPLegisResponseDTO;
import br.com.sampachat.api.model.Projeto;
import br.com.sampachat.api.model.TipoProposicao;
import br.com.sampachat.api.repository.ProjetoRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Serviço responsável por sincronizar projetos do SPLegis com o banco de dados local
 */
@Service
public class SyncProjetosService {
    
    private static final Logger logger = LoggerFactory.getLogger(SyncProjetosService.class);
    private static final String BASE_URL = "https://splegisconsulta.saopaulo.sp.leg.br/Pesquisa/PageDataProjeto";
    public static final Map<Integer, TipoProposicao> TIPO_MAP = new HashMap<>();
    
    static {
        // Mapeamento entre o tipo da API SPLegis e nosso enum TipoProposicao
        TIPO_MAP.put(1, TipoProposicao.PL);    // 1 = PL (Projeto de Lei)
        TIPO_MAP.put(2, TipoProposicao.PDL);   // 2 = PDL (Projeto de Decreto Legislativo)
        TIPO_MAP.put(3, TipoProposicao.PR);    // 3 = PR (Projeto de Resolução)
        TIPO_MAP.put(4, TipoProposicao.PLO);   // 4 = PLO (Projeto de Emenda à Lei Orgânica)
    }
    
    @Autowired
    private ProjetoRepository projetoRepository;
    
    @Autowired
    private EmbeddingService embeddingService;
    
    @Autowired
    private LinkBuilderService linkBuilderService;
    
    @Autowired
    private RestTemplate restTemplate;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Autowired
    private SyncStatusService syncStatusService;
    
    @Autowired
    private AlertService alertService;
    
    @Value("${app.sync.retry.max-attempts:3}")
    private int maxRetryAttempts;
    
    @Value("${app.sync.retry.delay-ms:30000}")
    private long retryDelayMs;

    /**
     * Sincroniza projetos de todos os tipos com mecanismo de retry
     * @return true se a sincronização foi bem-sucedida, false caso contrário
     */
    public boolean syncAllTiposWithRetry() {
        int attempts = 0;
        boolean success = false;
        Exception lastException = null;
        
        while (!success && attempts < maxRetryAttempts) {
            attempts++;
            
            try {
                if (attempts > 1) {
                    logger.info("Tentativa {} de sincronização após falha anterior", attempts);
                    
                    // Aguarda antes de tentar novamente
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.warn("Thread interrompida durante espera para retry");
                    }
                }
                
                // Realiza a sincronização
                syncAllTipos();
                
                // Se chegou aqui, a sincronização foi bem-sucedida
                success = true;
                logger.info("Sincronização bem-sucedida na tentativa {}", attempts);
                
            } catch (Exception e) {
                lastException = e;
                logger.error("Falha na tentativa {} de sincronização: {}", attempts, e.getMessage(), e);
                
                if (attempts < maxRetryAttempts) {
                    logger.info("Tentando novamente em {} ms", retryDelayMs);
                }
            }
        }
        
        // Atualiza o contador de tentativas no último registro de status
        syncStatusService.updateLastEntryRetryCount(attempts - 1);
        
        if (!success && lastException != null) {
            logger.error("Todas as {} tentativas de sincronização falharam. Último erro: {}", 
                    maxRetryAttempts, lastException.getMessage());
        }
        
        return success;
    }
    
    /**
     * Busca os projetos mais recentes de cada tipo no banco de dados
     */
    public Map<TipoProposicao, Projeto> findLatestProjetosByTipo() {
        Map<TipoProposicao, Projeto> latestProjetos = new HashMap<>();
        
        for (TipoProposicao tipo : TipoProposicao.values()) {
            try {
                // Usando o método JPQL ao invés da query nativa para evitar problemas de casting
                List<Projeto> projetos = projetoRepository.findLatestByTipoJpql(
                        tipo, 
                        org.springframework.data.domain.PageRequest.of(0, 1)
                );
                
                if (!projetos.isEmpty()) {
                    latestProjetos.put(tipo, projetos.get(0));
                    logger.info("Projeto mais recente do tipo {}: {}/{}", 
                            tipo, projetos.get(0).getNumero(), projetos.get(0).getAno());
                } else {
                    logger.info("Nenhum projeto do tipo {} encontrado no banco de dados", tipo);
                }
            } catch (Exception e) {
                logger.error("Erro ao buscar projetos do tipo {}: {}", tipo, e.getMessage(), e);
            }
        }
        
        return latestProjetos;
    }
    
    /**
     * Inicia uma sincronização assíncrona em segundo plano
     * Este método retorna imediatamente enquanto a sincronização ocorre em uma thread separada
     */
    public void startAsyncSync() {
        logger.info("Iniciando sincronização assíncrona");
        
        // Executa a sincronização em uma thread separada
        asyncSync();
    }
    
    /**
     * Método assíncrono que executa a sincronização em segundo plano
     * A anotação @Async faz com que este método seja executado em uma thread separada
     */
    @Async
    public void asyncSync() {
        logger.info("=== INÍCIO DA SINCRONIZAÇÃO ASSÍNCRONA ===");
        logger.info("Sincronização assíncrona iniciada em {}", 
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        
        try {
            // Verificar e remover duplicatas antes da sincronização
            removeDuplicateProjetosIfNeeded();
            
            // Executar sincronização incremental em vez da sincronização completa
            syncProjetosSinceLastSync();
            
            logger.info("=== SINCRONIZAÇÃO ASSÍNCRONA CONCLUÍDA COM SUCESSO ===");
        } catch (Exception e) {
            logger.error("=== ERRO FATAL NA SINCRONIZAÇÃO ASSÍNCRONA: {} ===", e.getMessage(), e);
            // Registra o erro no SyncStatusService
            syncStatusService.registerSyncFailure("Erro fatal: " + e.getMessage());
            // Envia alerta
            alertService.sendSyncFailureAlert("Erro fatal na sincronização assíncrona: " + e.getMessage());
        }
    }
    
    /**
     * Método para executar a sincronização (anteriormente agendado, agora disparado via endpoint)
     */
    public void scheduledSync() {
        logger.info("=== INÍCIO DA SINCRONIZAÇÃO AGENDADA ===");
        logger.info("Iniciando sincronização agendada de projetos às {}", 
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        
        try {
            // Verificar e remover duplicatas antes da sincronização
            removeDuplicateProjetosIfNeeded();
            
            // Executar sincronização incremental em vez da sincronização completa
            // Isso é mais eficiente e mais resiliente a falhas
            syncProjetosSinceLastSync();
            logger.info("=== SINCRONIZAÇÃO AGENDADA CONCLUÍDA COM SUCESSO ===");
        } catch (Exception e) {
            logger.error("=== ERRO FATAL NA SINCRONIZAÇÃO AGENDADA: {} ===", e.getMessage(), e);
            // Registra o erro no SyncStatusService
            syncStatusService.registerSyncFailure("Erro fatal: " + e.getMessage());
            // Envia alerta
            alertService.sendSyncFailureAlert("Erro fatal na sincronização: " + e.getMessage());
        }
    }
    
    /**
     * Sincroniza projetos a partir da última data de sincronização
     * Isso é útil para manter o banco atualizado sem precisar verificar todos os projetos
     */
    @Transactional
    public void syncProjetosSinceLastSync() {
        try {
            logger.info("Iniciando sincronização incremental de projetos desde a última sincronização");
            
            // Recupera a data da última sincronização bem-sucedida
            LocalDate lastSyncDate = syncStatusService.getLastSuccessfulSyncDate();
            if (lastSyncDate == null) {
                // Se não houver registro de sincronização anterior, usa uma data padrão (1 mês atrás)
                lastSyncDate = LocalDate.now().minusMonths(1);
                logger.info("Nenhuma sincronização anterior registrada. Usando data padrão: {}", lastSyncDate);
            } else {
                logger.info("Última sincronização bem-sucedida: {}", lastSyncDate);
            }
            
            // Ano atual
            int currentYear = Calendar.getInstance().get(Calendar.YEAR);
            
            // Sincroniza projetos de todos os tipos para o ano atual e, se necessário, ano anterior
            for (Map.Entry<Integer, TipoProposicao> entry : TIPO_MAP.entrySet()) {
                Integer spLegisTipo = entry.getKey();
                TipoProposicao tipo = entry.getValue();
                
                // Sincroniza projetos do ano atual começando do número 1
                // Isso garante que novos projetos sejam capturados
                syncProjetosByTipoAndYear(spLegisTipo, tipo, 1, currentYear);
                
                // Se a última sincronização foi no ano anterior ou se estamos no início do ano,
                // sincroniza também o ano anterior
                if (lastSyncDate.getYear() < currentYear || Calendar.getInstance().get(Calendar.MONTH) < 3) {
                    syncProjetosByTipoAndYear(spLegisTipo, tipo, 1, currentYear - 1);
                }
            }
            
            // Registra o sucesso da sincronização
            syncStatusService.registerSyncSuccess();
            logger.info("Sincronização incremental concluída com sucesso");
            
        } catch (Exception e) {
            String mensagem = "Erro durante a sincronização incremental: " + e.getMessage();
            logger.error(mensagem, e);
            syncStatusService.registerSyncFailure(mensagem);
            alertService.sendSyncFailureAlert(mensagem);
            throw new SyncProjetosException(mensagem, e);
        }
    }
    
    /**
     * Método para remover duplicatas antes da sincronização
     * Isso garante que não haverá problemas com a restrição única
     */
    @Transactional
    public void removeDuplicateProjetosIfNeeded() {
        try {
            logger.info("Verificando duplicatas na tabela projetos");
            
            // Conta o número de duplicatas
            Integer duplicateCount = 0;
            try {
                duplicateCount = projetoRepository.countDuplicates();
            } catch (Exception e) {
                logger.error("Erro ao contar duplicatas: {}", e.getMessage());
                return;
            }
            
            if (duplicateCount > 0) {
                logger.warn("Encontradas {} duplicatas na tabela projetos. Removendo...", duplicateCount);
                
                try {
                    projetoRepository.removeDuplicates();
                    logger.info("Duplicatas removidas com sucesso");
                } catch (Exception e) {
                    logger.error("Erro ao remover duplicatas: {}", e.getMessage());
                }
            } else {
                logger.info("Nenhuma duplicata encontrada na tabela projetos");
            }
        } catch (Exception e) {
            logger.error("Erro ao verificar/remover duplicatas: {}", e.getMessage());
        }
    }
    
    /**
     * Sincroniza projetos de todos os tipos
     */
    @Transactional
    public void syncAllTipos() {
        Map<TipoProposicao, Projeto> latestProjetos = findLatestProjetosByTipo();
        
        // Exibe informações detalhadas de cada projeto mais recente por tipo
        logger.info("=== PROJETOS MAIS RECENTES POR TIPO ===");
        for (Map.Entry<TipoProposicao, Projeto> entry : latestProjetos.entrySet()) {
            TipoProposicao tipo = entry.getKey();
            Projeto projeto = entry.getValue();
            
            if (projeto != null) {
                logger.info("TIPO: {} | Nº: {}/{} | AUTOR: {} | EMENTA: {}", 
                        tipo, 
                        projeto.getNumero(), 
                        projeto.getAno(),
                        projeto.getAutor() != null ? projeto.getAutor() : "N/A",
                        projeto.getEmenta() != null ? 
                                (projeto.getEmenta().length() > 100 ? 
                                        projeto.getEmenta().substring(0, 97) + "..." : 
                                        projeto.getEmenta()) : 
                                "N/A");
            } else {
                logger.info("TIPO: {} | Nenhum projeto encontrado", tipo);
            }
        }
        logger.info("=======================================");
        
        // Ano atual
        int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        
        for (Map.Entry<Integer, TipoProposicao> entry : TIPO_MAP.entrySet()) {
            Integer spLegisTipo = entry.getKey();
            TipoProposicao tipo = entry.getValue();
            
            Projeto latestProjeto = latestProjetos.get(tipo);
            
            // Se não houver projetos desse tipo, busca a partir do número 1
            int startNumero = latestProjeto != null ? latestProjeto.getNumero() + 1 : 1;
            
            // Sincroniza projetos do ano atual
            syncProjetosByTipoAndYear(spLegisTipo, tipo, startNumero, currentYear);
            
            // Se estamos no início do ano (Janeiro-Março), sincroniza também o ano anterior
            // para garantir que pegamos projetos de fim de ano
            if (Calendar.getInstance().get(Calendar.MONTH) < 3) {
                syncProjetosByTipoAndYear(spLegisTipo, tipo, 1, currentYear - 1);
            }
        }
    }
    
    /**
     * Sincroniza projetos de um tipo específico e ano
     */
    @Transactional
    public void syncProjetosByTipoAndYear(Integer spLegisTipo, TipoProposicao tipo, int startNumero, int year) {
        logger.info("Sincronizando projetos do tipo {} a partir do número {} do ano {}", 
                tipo, startNumero, year);
        
        // Número máximo para busca (limite arbitrário grande o suficiente)
        int endNumero = 9999;
        
        // Faz a busca em lotes menores para melhor controle e resiliência
        int batchSize = 500; // Tamanho reduzido para melhor performance
        
        try {
            for (int i = startNumero; i <= endNumero; i += batchSize) {
                int batchEnd = Math.min(i + batchSize - 1, endNumero);
                
                logger.info("Buscando lote de projetos {} a {} do tipo {} do ano {}", 
                        i, batchEnd, tipo, year);
                
                // Implementação de retry para a busca na API
                List<SPLegisProjetoDTO> projetos = null;
                int retryAttempts = 0;
                boolean success = false;
                
                while (!success && retryAttempts < 3) {
                    try {
                        projetos = fetchProjetosFromSPLegis(spLegisTipo, i, batchEnd, year);
                        success = true;
                    } catch (Exception e) {
                        retryAttempts++;
                        logger.warn("Falha na tentativa {} de buscar projetos {}-{}/{}: {}", 
                                retryAttempts, i, batchEnd, year, e.getMessage());
                        
                        if (retryAttempts < 3) {
                            // Aguarda antes de tentar novamente (com backoff exponencial)
                            try {
                                Thread.sleep(2000 * retryAttempts);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }
                }
                
                if (!success) {
                    logger.error("Falha em todas as tentativas de buscar projetos {}-{}/{}. Pulando este lote.", 
                            i, batchEnd, year);
                    continue; // Pula para o próximo lote
                }
                
                if (projetos == null || projetos.isEmpty()) {
                    logger.info("Nenhum projeto encontrado no intervalo {} a {} do tipo {} do ano {}", 
                            i, batchEnd, tipo, year);
                    // Se não há projetos neste lote, provavelmente chegamos ao fim dos projetos disponíveis
                    break;
                }
                
                logger.info("Encontrados {} projetos no intervalo {} a {} do tipo {} do ano {}", 
                        projetos.size(), i, batchEnd, tipo, year);
                
                // Converte e salva os projetos com tratamento de exceção
                try {
                    saveNewProjetos(projetos, tipo);
                } catch (Exception e) {
                    logger.error("Erro ao salvar lote de projetos {}-{}/{}: {}", 
                            i, batchEnd, year, e.getMessage(), e);
                    
                    // Notifica sobre o erro mas continua com o próximo lote
                    alertService.sendSyncFailureAlert(
                            String.format("Erro ao salvar lote %d-%d/%d do tipo %s: %s", 
                                    i, batchEnd, year, tipo, e.getMessage()));
                }
                
                // Se não chegamos ao fim do lote, provavelmente chegamos ao fim dos projetos disponíveis
                if (projetos.size() < batchSize) {
                    break;
                }
                
                // Pausa entre lotes para não sobrecarregar o sistema
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (Exception e) {
            String mensagem = String.format("Erro fatal ao sincronizar projetos do tipo %s do ano %d: %s", 
                    tipo, year, e.getMessage());
            logger.error(mensagem, e);
            alertService.sendSyncFailureAlert(mensagem);
            throw new SyncProjetosException(mensagem, e);
        }
    }
    
    /**
     * Busca projetos na API do SPLegis
     */
    public List<SPLegisProjetoDTO> fetchProjetosFromSPLegis(Integer tipo, int numeroInicio, int numeroFim, int ano) {
        try {
            // Formato da URL com os parâmetros necessários
            String url = BASE_URL + 
                    "?draw=5" +
                    "&columns%5B0%5D%5Bdata%5D=&columns%5B0%5D%5Bname%5D=&columns%5B0%5D%5Bsearchable%5D=false" +
                    "&columns%5B0%5D%5Borderable%5D=false&columns%5B0%5D%5Bsearch%5D%5Bvalue%5D=&columns%5B0%5D%5Bsearch%5D%5Bregex%5D=false" +
                    "&columns%5B1%5D%5Bdata%5D=1&columns%5B1%5D%5Bname%5D=PROJETO&columns%5B1%5D%5Bsearchable%5D=true" +
                    "&columns%5B1%5D%5Borderable%5D=true&columns%5B1%5D%5Bsearch%5D%5Bvalue%5D=&columns%5B1%5D%5Bsearch%5D%5Bregex%5D=false" +
                    "&columns%5B2%5D%5Bdata%5D=ementa&columns%5B2%5D%5Bname%5D=EMENTA&columns%5B2%5D%5Bsearchable%5D=true" +
                    "&columns%5B2%5D%5Borderable%5D=true&columns%5B2%5D%5Bsearch%5D%5Bvalue%5D=&columns%5B2%5D%5Bsearch%5D%5Bregex%5D=false" +
                    "&columns%5B3%5D%5Bdata%5D=norma&columns%5B3%5D%5Bname%5D=NORMA&columns%5B3%5D%5Bsearchable%5D=true" +
                    "&columns%5B3%5D%5Borderable%5D=true&columns%5B3%5D%5Bsearch%5D%5Bvalue%5D=&columns%5B3%5D%5Bsearch%5D%5Bregex%5D=false" +
                    "&columns%5B4%5D%5Bdata%5D=assuntos&columns%5B4%5D%5Bname%5D=PALAVRA&columns%5B4%5D%5Bsearchable%5D=true" +
                    "&columns%5B4%5D%5Borderable%5D=true&columns%5B4%5D%5Bsearch%5D%5Bvalue%5D=&columns%5B4%5D%5Bsearch%5D%5Bregex%5D=false" +
                    "&columns%5B5%5D%5Bdata%5D=promoventes&columns%5B5%5D%5Bname%5D=PROMOVENTE&columns%5B5%5D%5Bsearchable%5D=true" +
                    "&columns%5B5%5D%5Borderable%5D=true&columns%5B5%5D%5Bsearch%5D%5Bvalue%5D=&columns%5B5%5D%5Bsearch%5D%5Bregex%5D=false" +
                    "&order%5B0%5D%5Bcolumn%5D=1&order%5B0%5D%5Bdir%5D=desc&start=0&length=1000" +
                    "&search%5Bvalue%5D=&search%5Bregex%5D=false&assuntos=&naoAssuntos=&promoventes=&naoPromoventes=" +
                    "&tipo=" + tipo +
                    "&tipoPromovente=0&tipoVeto=0&promulgadoTipo=0&votacao=&somenteEmTramitacao=false" +
                    "&leituraInicio=&leituraFim=&autuacaoI=&autuacaoF=&tipoMotivoTramitacao=&localTramitacao=" +
                    "&motivoTramitacao=&leiOperador=%3D&leiNumero=&leiAno=" +
                    "&numeroInicio=" + numeroInicio +
                    "&numeroFim=" + numeroFim +
                    "&anoInicio=" + ano +
                    "&anoFim=" + ano +
                    "&_=" + System.currentTimeMillis();
            
            // Fazer a requisição HTTP
            String response = restTemplate.getForObject(url, String.class);
            
            // Parsear a resposta JSON
            SPLegisResponseDTO spLegisResponse = objectMapper.readValue(response, SPLegisResponseDTO.class);
            
            if (spLegisResponse != null && spLegisResponse.getData() != null) {
                return spLegisResponse.getData();
            } else {
                logger.warn("Resposta vazia ou inválida da API SPLegis");
                return Collections.emptyList();
            }
            
        } catch (Exception e) {
            logger.error("Erro ao buscar projetos da API SPLegis: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }
    
    /**
     * Converte e salva novos projetos usando upsert para evitar conflitos
     */
    @Transactional
    public void saveNewProjetos(List<SPLegisProjetoDTO> projetosDTO, TipoProposicao tipo) {
        if (projetosDTO.isEmpty()) {
            logger.info("Nenhum projeto do tipo {} para processar", tipo);
            return;
        }
        
        logger.info("Processando {} projetos do tipo {}", projetosDTO.size(), tipo);
        List<Projeto> projetosProcessados = new ArrayList<>();
        
        // Tamanho do lote para processamento de upsert
        final int BATCH_SIZE = 50;
        
        try {
            // Processa em lotes para melhorar a performance
            for (int i = 0; i < projetosDTO.size(); i += BATCH_SIZE) {
                int fim = Math.min(i + BATCH_SIZE, projetosDTO.size());
                List<SPLegisProjetoDTO> loteDTO = projetosDTO.subList(i, fim);
                
                logger.info("Processando lote de upsert {}/{} (projetos {} a {})", 
                        (i / BATCH_SIZE) + 1, 
                        (int) Math.ceil((double) projetosDTO.size() / BATCH_SIZE), 
                        i + 1, fim);
                
                List<Projeto> loteProjetos = new ArrayList<>();
                
                // Converte os DTOs para entidades
                for (SPLegisProjetoDTO dto : loteDTO) {
                    try {
                        Projeto projeto = new Projeto();
                        projeto.setTipo(tipo);
                        projeto.setNumero(dto.getNumero());
                        projeto.setAno(dto.getAno());
                        projeto.setEmenta(dto.getEmenta());
                        
                        // Extrair autores
                        if (dto.getPromoventes() != null && !dto.getPromoventes().isEmpty()) {
                            String autores = dto.getPromoventes().stream()
                                    .map(SPLegisProjetoDTO.ItemDTO::getTexto)
                                    .collect(Collectors.joining(", "));
                            projeto.setAutor(autores);
                            projeto.setAutorSearch(normalizarTexto(autores));
                        }
                        
                        // Extrair palavras-chave
                        if (dto.getAssuntos() != null && !dto.getAssuntos().isEmpty()) {
                            String palavrasChave = dto.getAssuntos().stream()
                                    .map(SPLegisProjetoDTO.ItemDTO::getTexto)
                                    .collect(Collectors.joining("|"));
                            projeto.setPalavrasChave(palavrasChave);
                        }
                        
                        loteProjetos.add(projeto);
                    } catch (Exception e) {
                        logger.error("Erro ao converter DTO para projeto {}/{} do tipo {}: {}", 
                                dto.getNumero(), dto.getAno(), tipo, e.getMessage());
                    }
                }
                
                // Executa o upsert em lote
                for (Projeto projeto : loteProjetos) {
                    try {
                        // Usar o método de upsert em lote
                        projetoRepository.upsertProjetoBatch(
                            projeto.getTipo().name(),
                            projeto.getNumero(),
                            projeto.getAno(),
                            projeto.getAutor(),
                            projeto.getAutorSearch(),
                            projeto.getEmenta(),
                            projeto.getPalavrasChave()
                        );
                        
                        // Adiciona à lista de projetos processados
                        projetosProcessados.add(projeto);
                    } catch (Exception e) {
                        logger.error("Erro ao executar upsert para projeto {}/{} do tipo {}: {}", 
                                projeto.getNumero(), projeto.getAno(), projeto.getTipo(), e.getMessage());
                    }
                }
            }
            
            // Recupera os projetos que precisam gerar embeddings
            // Precisa buscar novamente do banco, pois o upsert não retorna os IDs
            List<Projeto> projetosParaEmbedding = new ArrayList<>();
            
            // Busca em lotes para não sobrecarregar o banco
            for (int i = 0; i < projetosProcessados.size(); i += BATCH_SIZE) {
                int fim = Math.min(i + BATCH_SIZE, projetosProcessados.size());
                List<Projeto> lote = projetosProcessados.subList(i, fim);
                
                for (Projeto projeto : lote) {
                    try {
                        Projeto projetoCompleto = projetoRepository.findByTipoAndNumeroAndAno(
                                projeto.getTipo(), projeto.getNumero(), projeto.getAno());
                        
                        if (projetoCompleto != null && projetoCompleto.getEmbedding() == null) {
                            projetosParaEmbedding.add(projetoCompleto);
                        }
                    } catch (Exception e) {
                        logger.error("Erro ao buscar projeto para embedding {}/{} do tipo {}: {}", 
                                projeto.getNumero(), projeto.getAno(), projeto.getTipo(), e.getMessage());
                    }
                }
            }
            
            // Logs informativos
            logger.info("Processamento concluído para o tipo {}: {} projetos processados", 
                    tipo, projetosProcessados.size());
            
            // Gerar embeddings apenas para projetos que precisam
            if (!projetosParaEmbedding.isEmpty()) {
                logger.info("Gerando embeddings para {} projetos do tipo {}", 
                        projetosParaEmbedding.size(), tipo);
                generateEmbeddingsForNewProjetos(projetosParaEmbedding);
            }
            
        } catch (Exception e) {
            String mensagem = String.format("Erro durante o processamento de projetos do tipo %s: %s", 
                    tipo, e.getMessage());
            logger.error(mensagem, e);
            throw new SyncProjetosException(mensagem, e);
        }
    }
    
    /**
     * Gera embeddings para novos projetos
     */
    @Transactional
    public void generateEmbeddingsForNewProjetos(List<Projeto> projetos) {
        // Tamanho do lote para processamento de embeddings
        final int BATCH_SIZE = 64;
        
        if (projetos.isEmpty()) {
            return;
        }
        
        logger.info("Gerando embeddings para {} novos projetos", projetos.size());
        
        for (int i = 0; i < projetos.size(); i += BATCH_SIZE) {
            // Cria um sub-lote da lista principal
            int fim = Math.min(i + BATCH_SIZE, projetos.size());
            List<Projeto> lote = projetos.subList(i, fim);
            
            logger.info("Processando lote de embeddings {}/{} (projetos {} a {})", 
                    (i / BATCH_SIZE) + 1, 
                    (projetos.size() / BATCH_SIZE) + 1, 
                    i, fim);
            
            // Cria os textos para gerar embeddings
            List<String> textosParaEmbeddar = lote.stream()
                    .map(p -> {
                        // Constrói o texto para embedding apenas com ementa e palavras-chave
                        String ementa = "Ementa: " + (p.getEmenta() != null ? p.getEmenta() : "") + ". ";
                        // Adiciona as palavras-chave, substituindo a barra por espaços para um texto mais natural
                        String palavrasChave = "Palavras-chave: " + 
                                (p.getPalavrasChave() != null ? p.getPalavrasChave().replace("|", " ") : "") + ".";

                        return ementa + palavrasChave;
                    })
                    .collect(Collectors.toList());
            
            try {
                // Gera os embeddings apenas para o lote atual
                float[][] embeddingsDoLote = embeddingService.generateEmbeddings(textosParaEmbeddar);
                
                // Associa cada embedding de volta ao seu projeto original
                for (int j = 0; j < lote.size(); j++) {
                    Projeto projeto = lote.get(j);
                    projeto.setEmbedding(embeddingsDoLote[j]);
                }
                
                // Salva os projetos atualizados imediatamente após cada lote
                projetoRepository.saveAll(lote);
                logger.info("Lote {}/{} de embeddings gerado e salvo com sucesso", 
                        (i / BATCH_SIZE) + 1, 
                        (projetos.size() / BATCH_SIZE) + 1);
                
            } catch (Exception e) {
                logger.error("Erro ao gerar embeddings para o lote {}: {}", 
                        (i / BATCH_SIZE) + 1, e.getMessage(), e);
            }
        }
        
        logger.info("Embeddings gerados e salvos com sucesso para {} projetos", projetos.size());
    }
    
    // Método auxiliar para normalizar texto para busca (minúsculas, sem acentos)
    private String normalizarTexto(String texto) {
        if (texto == null) return "";
        String textoNormalizado = Normalizer.normalize(texto, Normalizer.Form.NFD);
        Pattern pattern = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
        return pattern.matcher(textoNormalizado).replaceAll("").toLowerCase();
    }
}