package br.com.sampachat.api.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DTO para expor configurações do sistema para o frontend
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ConfigDTO {
    private int defaultPageSize;
    private int maxResultsLimit;
}
