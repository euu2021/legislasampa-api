package br.com.sampachat.api.dto.splegis;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * DTO para representar a resposta paginada da API do SPLegis
 */
@Data
public class SPLegisResponseDTO {
    private Integer draw;
    private Integer recordsTotal;
    private Integer recordsFiltered;
    private List<SPLegisProjetoDTO> data;
    private String error;
}
