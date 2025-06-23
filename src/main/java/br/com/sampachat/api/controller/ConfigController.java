package br.com.sampachat.api.controller;

import br.com.sampachat.api.dto.ConfigDTO;
import br.com.sampachat.api.util.AppConstants;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller para expor configurações do sistema
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {
    
    @GetMapping
    public ResponseEntity<ConfigDTO> getConfig() {
        ConfigDTO config = new ConfigDTO(
            AppConstants.DEFAULT_PAGE_SIZE,
            AppConstants.MAX_RESULTS_LIMIT
        );
        return ResponseEntity.ok(config);
    }
}
