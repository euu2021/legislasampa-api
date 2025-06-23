package br.com.sampachat.api.service;

import br.com.sampachat.api.dto.splegis.SPLegisProjetoDTO;
import br.com.sampachat.api.model.Projeto;
import br.com.sampachat.api.model.TipoProposicao;
import br.com.sampachat.api.repository.ProjetoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Serviço responsável por verificar e corrigir a integridade dos dados no banco
 */
@Service
public class DataIntegrityService {
    
    private static final Logger logger = LoggerFactory.getLogger(DataIntegrityService.class);
    
    @Autowired
    private ProjetoRepository projetoRepository;
    
    @Autowired
    private SyncProjetosService syncProjetosService;
    
    @Autowired
    private SyncStatusService syncStatusService;
    
    @Autowired
    private AlertService alertService;
    
    /**
     * Executa verificações de integridade diariamente às 4:00 da manhã (após a sincronização)
     */
    @Scheduled(cron = "${app.integrity.cron:0 0 4 * * ?}")
    public void scheduledIntegrityCheck() {
        logger.info("=== INÍCIO DA VERIFICAÇÃO DE INTEGRIDADE AGENDADA ===");
        
        try {
            // Verificar projetos faltantes
            checkMissingProjetos();
            
            // Verificar projetos sem palavras-chave
            checkProjetosWithoutPalavrasChave();
            
            logger.info("=== VERIFICAÇÃO DE INTEGRIDADE CONCLUÍDA COM SUCESSO ===");
        } catch (Exception e) {
            logger.error("=== ERRO NA VERIFICAÇÃO DE INTEGRIDADE: {} ===", e.getMessage(), e);
            alertService.sendSyncFailureAlert("Erro na verificação de integridade: " + e.getMessage());
        }
    }
    
    /**
     * Verifica projetos faltantes no ano atual
     */
    @Transactional
    public void checkMissingProjetos() {
        int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        
        logger.info("Verificando projetos faltantes para o ano {}", currentYear);
        
        for (TipoProposicao tipo : TipoProposicao.values()) {
            try {
                // Busca o número máximo para este tipo e ano
                Integer maxNumero = projetoRepository.findMaxNumeroByTipoAndAno(tipo, currentYear);
                
                if (maxNumero == null || maxNumero == 0) {
                    logger.info("Nenhum projeto do tipo {} encontrado para o ano {}", tipo, currentYear);
                    continue;
                }
                
                logger.info("Número máximo para {} em {}: {}", tipo, currentYear, maxNumero);
                
                // Busca todos os números existentes
                List<Integer> existingNumeros = projetoRepository.findAllNumerosByTipoAndAno(tipo, currentYear);
                
                // Cria um conjunto para busca eficiente
                Set<Integer> existingNumerosSet = new HashSet<>(existingNumeros);
                
                // Encontra números faltantes
                List<Integer> missingNumeros = IntStream.rangeClosed(1, maxNumero)
                        .filter(n -> !existingNumerosSet.contains(n))
                        .boxed()
                        .collect(Collectors.toList());
                
                if (missingNumeros.isEmpty()) {
                    logger.info("Nenhum projeto faltante do tipo {} para o ano {}", tipo, currentYear);
                    continue;
                }
                
                logger.info("Encontrados {} projetos faltantes do tipo {} para o ano {}: {}", 
                        missingNumeros.size(), tipo, currentYear, missingNumeros);
                
                // Busca os projetos faltantes em lotes para não sobrecarregar a API
                fetchMissingProjetos(tipo, missingNumeros, currentYear);
                
            } catch (Exception e) {
                logger.error("Erro ao verificar projetos faltantes do tipo {}: {}", tipo, e.getMessage(), e);
            }
        }
    }
    
    /**
     * Busca projetos faltantes da API
     */
    private void fetchMissingProjetos(TipoProposicao tipo, List<Integer> missingNumeros, int year) {
        // Identifica o tipo na API SPLegis
        Integer spLegisTipo = null;
        for (Map.Entry<Integer, TipoProposicao> entry : SyncProjetosService.TIPO_MAP.entrySet()) {
            if (entry.getValue() == tipo) {
                spLegisTipo = entry.getKey();
                break;
            }
        }
        
        if (spLegisTipo == null) {
            logger.error("Não foi possível identificar o código SPLegis para o tipo {}", tipo);
            return;
        }
        
        // Busca em lotes de 50 para não sobrecarregar a API
        int batchSize = 50;
        
        for (int i = 0; i < missingNumeros.size(); i += batchSize) {
            int endIndex = Math.min(i + batchSize, missingNumeros.size());
            List<Integer> batch = missingNumeros.subList(i, endIndex);
            
            logger.info("Buscando lote de projetos faltantes {}/{} do tipo {} ({})", 
                    (i / batchSize) + 1, 
                    (missingNumeros.size() / batchSize) + 1, 
                    tipo, batch);
            
            // Para cada projeto faltante, busca na API
            for (Integer numero : batch) {
                try {
                    List<SPLegisProjetoDTO> projetos = syncProjetosService.fetchProjetosFromSPLegis(
                            spLegisTipo, numero, numero, year);
                    
                    if (projetos.isEmpty()) {
                        logger.warn("Projeto {}/{} do tipo {} não encontrado na API SPLegis", 
                                numero, year, tipo);
                        continue;
                    }
                    
                    // Salva os projetos encontrados
                    syncProjetosService.saveNewProjetos(projetos, tipo);
                    
                    logger.info("Projeto {}/{} do tipo {} recuperado com sucesso", numero, year, tipo);
                    
                } catch (Exception e) {
                    logger.error("Erro ao buscar projeto {}/{} do tipo {}: {}", 
                            numero, year, tipo, e.getMessage(), e);
                }
            }
        }
    }
    
    /**
     * Verifica projetos sem palavras-chave
     */
    @Transactional
    public void checkProjetosWithoutPalavrasChave() {
        logger.info("Verificando projetos sem palavras-chave");
        
        // Número máximo de projetos para processar por vez
        final int BATCH_SIZE = 100;
        
        // Busca projetos sem palavras-chave em lotes
        List<Projeto> projetosWithoutPalavrasChave = projetoRepository.findProjetosWithoutPalavrasChave(
                PageRequest.of(0, BATCH_SIZE));
        
        if (projetosWithoutPalavrasChave.isEmpty()) {
            logger.info("Nenhum projeto sem palavras-chave encontrado");
            return;
        }
        
        logger.info("Encontrados {} projetos sem palavras-chave", projetosWithoutPalavrasChave.size());
        
        // Para cada projeto sem palavras-chave, busca as informações atualizadas
        for (Projeto projeto : projetosWithoutPalavrasChave) {
            try {
                // Identifica o tipo na API SPLegis
                Integer spLegisTipo = null;
                for (Map.Entry<Integer, TipoProposicao> entry : SyncProjetosService.TIPO_MAP.entrySet()) {
                    if (entry.getValue() == projeto.getTipo()) {
                        spLegisTipo = entry.getKey();
                        break;
                    }
                }
                
                if (spLegisTipo == null) {
                    logger.error("Não foi possível identificar o código SPLegis para o tipo {}", 
                            projeto.getTipo());
                    continue;
                }
                
                // Busca as informações atualizadas na API
                List<SPLegisProjetoDTO> projetos = syncProjetosService.fetchProjetosFromSPLegis(
                        spLegisTipo, projeto.getNumero(), projeto.getNumero(), projeto.getAno());
                
                if (projetos.isEmpty()) {
                    logger.warn("Projeto {}/{} do tipo {} não encontrado na API SPLegis", 
                            projeto.getNumero(), projeto.getAno(), projeto.getTipo());
                    continue;
                }
                
                SPLegisProjetoDTO dto = projetos.get(0);
                
                // Verifica se agora tem palavras-chave
                if (dto.getAssuntos() == null || dto.getAssuntos().isEmpty()) {
                    logger.warn("Projeto {}/{} do tipo {} ainda não possui palavras-chave na API SPLegis", 
                            projeto.getNumero(), projeto.getAno(), projeto.getTipo());
                    continue;
                }
                
                // Atualiza as palavras-chave
                String palavrasChave = dto.getAssuntos().stream()
                        .map(SPLegisProjetoDTO.ItemDTO::getTexto)
                        .collect(Collectors.joining("|"));
                
                projeto.setPalavrasChave(palavrasChave);
                projetoRepository.save(projeto);
                
                logger.info("Palavras-chave atualizadas para o projeto {}/{} do tipo {}", 
                        projeto.getNumero(), projeto.getAno(), projeto.getTipo());
                
                // Regenera o embedding para este projeto
                List<Projeto> projetos_para_embedding = new ArrayList<>();
                projetos_para_embedding.add(projeto);
                syncProjetosService.generateEmbeddingsForNewProjetos(projetos_para_embedding);
                
            } catch (Exception e) {
                logger.error("Erro ao atualizar palavras-chave do projeto {}/{} do tipo {}: {}", 
                        projeto.getNumero(), projeto.getAno(), projeto.getTipo(), e.getMessage(), e);
            }
        }
    }
}
