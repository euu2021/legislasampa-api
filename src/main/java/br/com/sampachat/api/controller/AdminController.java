package br.com.sampachat.api.controller;

import br.com.sampachat.api.service.CargaDadosService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired
    private CargaDadosService cargaDadosService;

    @PostMapping("/load-data")
    public ResponseEntity<String> loadData() {
        System.out.println(">>> Requisição de carga de dados recebida. Iniciando processo...");

        // Inicia a carga em uma nova thread para não bloquear a resposta HTTP
        new Thread(() -> {
            cargaDadosService.carregarDadosDaPlanilha();
            cargaDadosService.gerarEmbeddings();
        }).start();

        String message = "Processo de carga de dados iniciado em segundo plano. " +
                "Acompanhe o progresso pelos logs no painel do Render. " +
                "Isso pode levar muitos minutos.";

        return ResponseEntity.ok(message);
    }
}