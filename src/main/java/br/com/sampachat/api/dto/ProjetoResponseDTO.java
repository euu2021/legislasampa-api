package br.com.sampachat.api.dto;

import br.com.sampachat.api.model.Projeto;
import br.com.sampachat.api.model.TipoProposicao;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ProjetoResponseDTO {
    private Integer id;
    private TipoProposicao tipo;
    private Integer numero;
    private Integer ano;
    private String autor;
    private String autorSearch;
    private String ementa;
    private String palavrasChave;
    private String linkSpLegis;
    private String linkPortal;
    private String linkPdf;
    
    public ProjetoResponseDTO(Projeto projeto, String linkSpLegis, String linkPortal, String linkPdf) {
        this.id = projeto.getId();
        this.tipo = projeto.getTipo();
        this.numero = projeto.getNumero();
        this.ano = projeto.getAno();
        this.autor = projeto.getAutor();
        this.autorSearch = projeto.getAutorSearch();
        this.ementa = projeto.getEmenta();
        this.palavrasChave = projeto.getPalavrasChave();
        this.linkSpLegis = linkSpLegis;
        this.linkPortal = linkPortal;
        this.linkPdf = linkPdf;
    }
}