package br.com.sampachat.api.service;

import br.com.sampachat.api.model.TipoProposicao;
import org.springframework.stereotype.Service;

/**
 * Serviço para construir links relacionados a proposições legislativas
 */
@Service
public class LinkBuilderService {
    
    /**
     * Constrói o link para o SPLegis
     */
    public String buildSpLegisLink(TipoProposicao tipo, Integer numero, Integer ano) {
        return String.format(
                "https://splegisconsulta.saopaulo.sp.leg.br/Pesquisa/DetailsDetalhado?COD_MTRA_LEGL=%d&COD_PCSS_CMSP=%d&ANO_PCSS_CMSP=%d",
                getCodigoTipoMateria(tipo), numero, ano
        );
    }
    
    /**
     * Constrói o link para o Portal da Câmara
     */
    public String buildPortalLink(TipoProposicao tipo, Integer numero, Integer ano) {
        String tipoStr = tipo.name();
        // Concatena número e ano sem separador
        return String.format(
                "https://www.saopaulo.sp.leg.br/cgi-bin/wxis.bin/iah/scripts/?IsisScript=iah.xis&lang=pt&format=detalhado.pft&base=proje&form=A&nextAction=search&indexSearch=^nTw^lTodos%%20os%%20campos&exprSearch=P=%s%d%d", 
                tipoStr, numero, ano);
    }
    
    /**
     * Constrói o link para o PDF do projeto
     */
    public String buildPdfLink(TipoProposicao tipo, Integer numero, Integer ano) {
        String tipoStr = tipo.name();
        String numeroFormatado = String.format("%04d", numero);
        return String.format(
                "https://www.saopaulo.sp.leg.br/iah/fulltext/projeto/%s%s-%s.pdf", 
                tipoStr, numeroFormatado, ano);
    }
    
    /**
     * Retorna o código da matéria legislativa conforme o tipo
     */
    private int getCodigoTipoMateria(TipoProposicao tipo) {
        switch (tipo) {
            case PL:
                return 1;
            case PLO:
                return 3;
            case PDL:
                return 2;
            case PR:
                return 4;
            default:
                return 0;
        }
    }
}