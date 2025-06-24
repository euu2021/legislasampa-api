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
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
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
     * Método para executar a sincronização (anteriormente agendado, agora disparado via endpoint)
     */
    public void scheduledSync() {
        logger.info("=== INÍCIO DA SINCRONIZAÇÃO AGENDADA ===");
        logger.info("Iniciando sincronização agendada de projetos às {}", 
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        
        try {
            boolean success = syncAllTiposWithRetry();
            
            if (success) {
                logger.info("=== SINCRONIZAÇÃO AGENDADA CONCLUÍDA COM SUCESSO ===");
                // Registra o sucesso no SyncStatusService
                syncStatusService.registerSyncSuccess();
            } else {
                logger.error("=== SINCRONIZAÇÃO AGENDADA FALHOU APÓS TENTATIVAS DE RETRY ===");
                // Registra a falha no SyncStatusService
                syncStatusService.registerSyncFailure("Falha após tentativas de retry");
                // Envia alerta
                alertService.sendSyncFailureAlert("Falha na sincronização agendada após tentativas de retry");
            }
        } catch (Exception e) {
            logger.error("=== ERRO FATAL NA SINCRONIZAÇÃO AGENDADA: {} ===", e.getMessage(), e);
            // Registra o erro no SyncStatusService
            syncStatusService.registerSyncFailure("Erro fatal: " + e.getMessage());
            // Envia alerta
            alertService.sendSyncFailureAlert("Erro fatal na sincronização: " + e.getMessage());
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
        
        // Faz a busca em lotes de 1000 para não sobrecarregar a API
        int batchSize = 1000;
        
        for (int i = startNumero; i <= endNumero; i += batchSize) {
            int batchEnd = Math.min(i + batchSize - 1, endNumero);
            
            logger.info("Buscando lote de projetos {} a {} do tipo {} do ano {}", 
                    i, batchEnd, tipo, year);
            
            List<SPLegisProjetoDTO> projetos = fetchProjetosFromSPLegis(spLegisTipo, i, batchEnd, year);
            
            if (projetos.isEmpty()) {
                logger.info("Nenhum projeto encontrado no intervalo {} a {} do tipo {} do ano {}", 
                        i, batchEnd, tipo, year);
                // Se não há projetos neste lote, provavelmente chegamos ao fim dos projetos disponíveis
                break;
            }
            
            logger.info("Encontrados {} projetos no intervalo {} a {} do tipo {} do ano {}", 
                    projetos.size(), i, batchEnd, tipo, year);
            
            // Converte e salva os projetos
            saveNewProjetos(projetos, tipo);
            
            // Se não chegamos ao fim do lote, provavelmente chegamos ao fim dos projetos disponíveis
            if (projetos.size() < batchSize) {
                break;
            }
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
     * Converte e salva novos projetos
     */
    @Transactional
    public void saveNewProjetos(List<SPLegisProjetoDTO> projetosDTO, TipoProposicao tipo) {
        List<Projeto> newProjetos = new ArrayList<>();
        
        for (SPLegisProjetoDTO dto : projetosDTO) {
            // Verifica se o projeto já existe no banco
            boolean exists = projetoRepository.existsByTipoAndNumeroAndAno(
                    tipo, dto.getNumero(), dto.getAno());
            
            if (!exists) {
                // Converter DTO para entidade
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
                
                // Os links agora são construídos dinamicamente pelo LinkBuilderService
                
                newProjetos.add(projeto);
            }
        }
        
        if (!newProjetos.isEmpty()) {
            logger.info("Salvando {} novos projetos do tipo {}", newProjetos.size(), tipo);
            projetoRepository.saveAll(newProjetos);
            
            // Gerar embeddings para os novos projetos
            generateEmbeddingsForNewProjetos(newProjetos);
        } else {
            logger.info("Nenhum projeto novo do tipo {} para salvar", tipo);
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