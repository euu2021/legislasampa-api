package br.com.sampachat.api.controller;

import br.com.sampachat.api.model.Projeto;
import br.com.sampachat.api.model.TipoProposicao;
import br.com.sampachat.api.service.DataIntegrityService;
import br.com.sampachat.api.service.SyncProjetosService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Controlador para operações administrativas
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    @Autowired
    private SyncProjetosService syncProjetosService;
    
    @Autowired
    private DataIntegrityService dataIntegrityService;

    /**
     * Endpoint para sincronizar projetos manualmente
     */
    @PostMapping("/sync-projetos")
    public ResponseEntity<String> syncProjetos() {
        try {
            boolean success = syncProjetosService.syncAllTiposWithRetry();
            
            if (success) {
                return ResponseEntity.ok("Sincronização de projetos concluída com sucesso.");
            } else {
                return ResponseEntity.status(500)
                        .body("Sincronização falhou após múltiplas tentativas. Verifique os logs para mais detalhes.");
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("Erro ao iniciar sincronização: " + e.getMessage());
        }
    }
    
    /**
     * Endpoint GET para facilitar testes de sincronização via navegador
     * Considere remover este endpoint em produção e usar apenas o POST
     */
    @GetMapping("/sync-projetos-test")
    public ResponseEntity<String> syncProjetosTest() {
        try {
            boolean success = syncProjetosService.syncAllTiposWithRetry();
            
            if (success) {
                return ResponseEntity.ok("Sincronização de projetos concluída com sucesso via teste GET.");
            } else {
                return ResponseEntity.status(500)
                        .body("Sincronização falhou após múltiplas tentativas. Verifique os logs para mais detalhes.");
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("Erro ao iniciar sincronização: " + e.getMessage());
        }
    }
    
    /**
     * Endpoint GET para visualizar os projetos mais recentes de cada tipo
     */
    @GetMapping("/latest-projetos")
    public ResponseEntity<String> getLatestProjetos() {
        try {
            Map<TipoProposicao, Projeto> latestProjetos = syncProjetosService.findLatestProjetosByTipo();
            
            StringBuilder response = new StringBuilder("PROJETOS MAIS RECENTES POR TIPO:\n\n");
            
            for (Map.Entry<TipoProposicao, Projeto> entry : latestProjetos.entrySet()) {
                TipoProposicao tipo = entry.getKey();
                Projeto projeto = entry.getValue();
                
                if (projeto != null) {
                    response.append(String.format("TIPO: %s | Nº: %d/%d | AUTOR: %s | EMENTA: %s\n\n", 
                            tipo, 
                            projeto.getNumero(), 
                            projeto.getAno(),
                            projeto.getAutor() != null ? projeto.getAutor() : "N/A",
                            projeto.getEmenta() != null ? 
                                    (projeto.getEmenta().length() > 100 ? 
                                            projeto.getEmenta().substring(0, 97) + "..." : 
                                            projeto.getEmenta()) : 
                                    "N/A"));
                } else {
                    response.append(String.format("TIPO: %s | Nenhum projeto encontrado\n\n", tipo));
                }
            }
            
            return ResponseEntity.ok(response.toString());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("Erro ao buscar projetos mais recentes: " + e.getMessage());
        }
    }
    
    /**
     * Endpoint para verificar projetos faltantes
     */
    @GetMapping("/check-missing")
    public ResponseEntity<String> checkMissingProjetos() {
        try {
            dataIntegrityService.checkMissingProjetos();
            return ResponseEntity.ok("Verificação de projetos faltantes iniciada com sucesso.");
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("Erro ao verificar projetos faltantes: " + e.getMessage());
        }
    }
    
    /**
     * Endpoint para verificar projetos sem palavras-chave
     */
    @GetMapping("/check-palavras-chave")
    public ResponseEntity<String> checkPalavrasChave() {
        try {
            dataIntegrityService.checkProjetosWithoutPalavrasChave();
            return ResponseEntity.ok("Verificação de projetos sem palavras-chave iniciada com sucesso.");
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("Erro ao verificar projetos sem palavras-chave: " + e.getMessage());
        }
    }
    
    /**
     * Endpoint para executar todas as verificações de integridade
     */
    @GetMapping("/check-integrity")
    public ResponseEntity<String> checkIntegrity() {
        try {
            dataIntegrityService.checkMissingProjetos();
            dataIntegrityService.checkProjetosWithoutPalavrasChave();
            return ResponseEntity.ok("Verificação de integridade iniciada com sucesso.");
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("Erro ao verificar integridade: " + e.getMessage());
        }
    }
}
