package br.com.sampachat.api.repository;

import br.com.sampachat.api.model.Projeto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProjetoRepository extends JpaRepository<Projeto, Integer> {

    List<Projeto> findByEmbeddingIsNull();

    // Busca Exata (Full-Text Search) - VAMOS FAZER ESTA FUNCIONAR
    @Query(
            value = "SELECT * FROM projetos " +
                    "WHERE ts_search @@ to_tsquery('portuguese', :query) " +
                    "LIMIT 20", // Removido o ORDER BY para simplificar
            nativeQuery = true
    )
    List<Projeto> searchByFTS(@Param("query") String query);

    // Busca Semântica (Aproximada) - Desativada temporariamente para focar no problema
    @Query(value = "SELECT * FROM projetos ORDER BY embedding <=> CAST(:queryVector AS vector) LIMIT 15", nativeQuery = true)
    List<Projeto> searchByEmbedding(@Param("queryVector") String queryVector);
}