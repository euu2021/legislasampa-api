package br.com.sampachat.api.service;

import br.com.sampachat.api.model.Projeto;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SearchService {

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private EmbeddingService embeddingService;

    // BUSCA APROXIMADA (SEMÂNTICA)
    public List<Projeto> searchSemantic(String userQuery) {
        try {
            System.out.println("Gerando embedding para a busca: \"" + userQuery + "\"");
            float[] queryEmbedding = embeddingService.generateEmbeddings(List.of(userQuery))[0];
            String vectorString = Arrays.toString(queryEmbedding);

            System.out.println("Executando busca vetorial no banco de dados...");

            // Usando EntityManager para garantir o funcionamento
            String sql = "SELECT * FROM projetos ORDER BY embedding <=> CAST(:queryVector AS vector) LIMIT 15";
            Query query = entityManager.createNativeQuery(sql, Projeto.class);
            query.setParameter("queryVector", vectorString);

            @SuppressWarnings("unchecked")
            List<Projeto> resultados = query.getResultList();

            System.out.println(resultados.size() + " resultados encontrados (busca semântica).");
            return resultados;

        } catch (Exception e) {
            System.err.println("Erro durante a busca semântica: " + e.getMessage());
            e.printStackTrace();
            return List.of();
        }
    }

    // BUSCA EXATA (FTS) - Usando a injeção de SQL que funcionou
    public List<Projeto> searchFTS(String userQuery) {
        try {
            String ftsQuery = Arrays.stream(userQuery.trim().split("\\s+"))
                    .filter(term -> !term.isBlank())
                    .map(term -> term.replace("'", "''") + ":*") // Sanitização básica
                    .collect(Collectors.joining(" & "));

            if (ftsQuery.isBlank()) {
                return List.of();
            }

            System.out.println("Executando busca por texto completo com a query injetada: '" + ftsQuery + "'");

            String sql = "SELECT * FROM projetos " +
                    "WHERE ts_search @@ to_tsquery('portuguese', '" + ftsQuery + "') " +
                    "ORDER BY ts_rank(ts_search, to_tsquery('portuguese', '" + ftsQuery + "')) DESC " +
                    "LIMIT 20";

            Query query = entityManager.createNativeQuery(sql, Projeto.class);

            @SuppressWarnings("unchecked")
            List<Projeto> resultados = query.getResultList();

            System.out.println(resultados.size() + " resultados encontrados (busca exata).");
            return resultados;
        } catch (Exception e) {
            System.err.println("Erro durante a busca FTS: " + e.getMessage());
            e.printStackTrace();
            return List.of();
        }
    }
}