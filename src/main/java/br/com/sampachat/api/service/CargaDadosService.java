package br.com.sampachat.api.service;

import br.com.sampachat.api.model.Projeto;
import br.com.sampachat.api.model.TipoProposicao;
import br.com.sampachat.api.repository.ProjetoRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.transaction.annotation.Propagation; // Novo import

@Service
public class CargaDadosService {

    @Autowired
    private ProjetoRepository projetoRepository;

    // Injeta o novo serviço de IA
    @Autowired
    private EmbeddingService embeddingService;

    // Padrão para extrair TIPO, NÚMERO e ANO da primeira coluna (ex: "PL 680/2025")
    private final Pattern padraoProjeto = Pattern.compile("(\\w+)\\s(\\d+)/(\\d{4})");

    @Transactional // Garante que toda a carga de dados ocorra em uma única transação
    public void carregarDadosDaPlanilha() {
        if (projetoRepository.count() > 0) {
            System.out.println("Banco de dados já populado. Carga de dados ignorada.");
            return;
        }

        System.out.println("Iniciando carga de dados a partir da planilha XLSX...");

        try (InputStream is = new ClassPathResource("projetos.xlsx").getInputStream()) {
            Workbook workbook = new XSSFWorkbook(is);
            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rows = sheet.iterator();

            if (rows.hasNext()) {
                rows.next(); // Pula a linha do cabeçalho
            }

            List<Projeto> projetosParaSalvar = new ArrayList<>();
            int contadorLinha = 1; // Começa na linha 2, já que pulamos o cabeçalho

            while (rows.hasNext()) {
                Row currentRow = rows.next();
                contadorLinha++;

                // Ignora linhas em branco verificando a primeira célula
                if (isRowEmpty(currentRow)) {
                    continue;
                }

                try {
                    String infoProjeto = getStringCellValue(currentRow.getCell(0)); // Coluna A: "PL 680/2025"
                    Matcher matcher = padraoProjeto.matcher(infoProjeto);

                    if (!matcher.find()) {
                        System.err.println("AVISO: Formato inválido na linha " + contadorLinha + ": " + infoProjeto);
                        continue; // Pula para a próxima linha
                    }

                    String tipoStr = matcher.group(1);
                    int numero = Integer.parseInt(matcher.group(2));
                    int ano = Integer.parseInt(matcher.group(3));

                    Projeto projeto = new Projeto();
                    projeto.setTipo(TipoProposicao.valueOf(tipoStr.toUpperCase()));
                    projeto.setNumero(numero);
                    projeto.setAno(ano);

                    // Colunas restantes
                    projeto.setEmenta(getStringCellValue(currentRow.getCell(1)));      // Coluna B: Ementa
                    projeto.setPalavrasChave(getStringCellValue(currentRow.getCell(2))); // Coluna C: Palavras-chave
                    projeto.setAutor(getStringCellValue(currentRow.getCell(3)));       // Coluna D: Promoventes

                    // Gerar campo de busca para o autor
                    if (projeto.getAutor() != null && !projeto.getAutor().isBlank()) {
                        projeto.setAutorSearch(normalizarTexto(projeto.getAutor()));
                    }

                    // Construir o link oficial
                    String link = String.format(
                            "https://splegisconsulta.saopaulo.sp.leg.br/Pesquisa/DetailsDetalhado?COD_MTRA_LEGL=1&COD_PCSS_CMSP=%d&ANO_PCSS_CMSP=%d",
                            numero, ano
                    );
                    projeto.setLinkOficial(link);

                    projetosParaSalvar.add(projeto);

                } catch (Exception e) {
                    System.err.println("Erro ao processar linha " + contadorLinha + ": " + e.getMessage());
                }
            }

            // Salva todos os projetos em lote (muito mais eficiente)
            if (!projetosParaSalvar.isEmpty()) {
                projetoRepository.saveAll(projetosParaSalvar);
            }

            System.out.println(projetosParaSalvar.size() + " projetos carregados com sucesso no banco de dados.");
            workbook.close();

        } catch (Exception e) {
            System.err.println("Erro fatal ao carregar dados da planilha: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Método auxiliar para normalizar texto para busca (minúsculas, sem acentos)
    private String normalizarTexto(String texto) {
        String textoNormalizado = Normalizer.normalize(texto, Normalizer.Form.NFD);
        Pattern pattern = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
        return pattern.matcher(textoNormalizado).replaceAll("").toLowerCase();
    }

    // Método auxiliar para ler o valor da célula como String de forma segura
    private String getStringCellValue(Cell cell) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.STRING) {
            return cell.getStringCellValue().trim();
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            // Converte número para string sem o ".0" no final
            return String.valueOf((long) cell.getNumericCellValue());
        }
        return "";
    }

    // Verifica se uma linha está vazia
    private boolean isRowEmpty(Row row) {
        if (row == null) {
            return true;
        }
        if (row.getLastCellNum() <= 0) {
            return true;
        }
        for (int cellNum = row.getFirstCellNum(); cellNum < row.getLastCellNum(); cellNum++) {
            Cell cell = row.getCell(cellNum);
            if (cell != null && cell.getCellType() != CellType.BLANK && !cell.toString().trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void gerarEmbeddings() {
        // Tamanho do lote que vamos processar por vez para não estourar a memória
        final int BATCH_SIZE = 64;

        List<Projeto> projetosSemEmbedding = projetoRepository.findByEmbeddingIsNull();

        if (projetosSemEmbedding.isEmpty()) {
            System.out.println("Todos os projetos já possuem embeddings. Nenhuma ação necessária.");
            return;
        }

        System.out.println("Gerando embeddings para " + projetosSemEmbedding.size() + " novos projetos em lotes de " + BATCH_SIZE + "...");

        List<Projeto> projetosAtualizados = new ArrayList<>();

        for (int i = 0; i < projetosSemEmbedding.size(); i += BATCH_SIZE) {
            // Cria um sub-lote da lista principal
            int fim = Math.min(i + BATCH_SIZE, projetosSemEmbedding.size());
            List<Projeto> lote = projetosSemEmbedding.subList(i, fim);

            System.out.println("Processando lote " + ((i / BATCH_SIZE) + 1) + "/" + ((projetosSemEmbedding.size() / BATCH_SIZE) + 1) + " (projetos " + i + " a " + fim + ")");

            // Cria o "super-texto" apenas para o lote atual
            List<String> textosParaEmbeddar = lote.stream()
                    .map(p -> "Tipo: " + p.getTipo().name() + ". Autor: " + p.getAutor() + ". Ementa: " + p.getEmenta())
                    .collect(Collectors.toList());

            try {
                // Gera os embeddings apenas para o lote atual
                float[][] embeddingsDoLote = embeddingService.generateEmbeddings(textosParaEmbeddar);

                // Associa cada embedding de volta ao seu projeto original
                for (int j = 0; j < lote.size(); j++) {
                    Projeto projeto = lote.get(j);
                    projeto.setEmbedding(embeddingsDoLote[j]);
                    projetosAtualizados.add(projeto);
                }

            } catch (Exception e) {
                System.err.println("Erro ao processar lote " + i + ". Pulando este lote.");
                e.printStackTrace();
            }
        }

        // Salva todos os projetos atualizados de uma vez só no final
        if (!projetosAtualizados.isEmpty()) {
            System.out.println("Salvando " + projetosAtualizados.size() + " projetos com embeddings no banco de dados...");
            projetoRepository.saveAll(projetosAtualizados);
            System.out.println("Embeddings salvos com sucesso.");
        }
    }
}