package br.com.sampachat.api.dto.splegis;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * DTO para representar a estrutura de resposta da API do SPLegis
 */
@Data
public class SPLegisProjetoDTO {
    private Long codigo;
    private Boolean natodigital;
    private Integer tipo;
    private Integer numero;
    private Integer ano;
    private String sigla;
    private String texto;
    private String ementa;
    private NormaDTO norma;
    private List<ItemDTO> promoventes;
    private List<ItemDTO> assuntos;
    
    @JsonProperty("dT_RowId")
    private Long dtRowId;

    @Data
    public static class NormaDTO {
        private Integer numero;
        private Integer ano;
    }

    @Data
    public static class ItemDTO {
        private Long codigo;
        private String texto;
    }
}
