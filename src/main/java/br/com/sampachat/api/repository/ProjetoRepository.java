package br.com.sampachat.api.repository;

import br.com.sampachat.api.model.Projeto;
import br.com.sampachat.api.model.TipoProposicao;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface ProjetoRepository extends JpaRepository<Projeto, Integer> {

    List<Projeto> findByEmbeddingIsNull();

    // Busca Exata (Full-Text Search)
    @Query(
            value = "SELECT * FROM projetos " +
                    "WHERE ts_search @@ to_tsquery('portuguese', :query) " +
                    "ORDER BY ano DESC, numero DESC " +
                    "LIMIT 100", // Limite ajustado para obter um número razoável de candidatos
            nativeQuery = true
    )
    List<Projeto> searchByFTS(@Param("query") String query);

    // Busca Semântica (Aproximada)
    @Query(value = "SELECT * FROM projetos ORDER BY embedding <=> CAST(:queryVector AS vector) LIMIT 100", nativeQuery = true)
    List<Projeto> searchByEmbedding(@Param("queryVector") String queryVector);

    @Query("SELECT DISTINCT p.autor FROM Projeto p WHERE p.autor IS NOT NULL")
    List<String> findDistinctAutores();
    
    // Verifica se um projeto já existe no banco de dados
    boolean existsByTipoAndNumeroAndAno(TipoProposicao tipo, Integer numero, Integer ano);
    
    // Busca o projeto mais recente por tipo (Query nativa)
    @Query(value = "SELECT * FROM projetos WHERE tipo = CAST(:#{#tipo.name()} AS tp_proposicao) " +
            "ORDER BY ano DESC, numero DESC LIMIT :limit", nativeQuery = true)
    List<Projeto> findLatestByTipo(@Param("tipo") TipoProposicao tipo, @Param("limit") int limit);
    
    // Método alternativo usando JPQL (não SQL nativo)
    @Query("SELECT p FROM Projeto p WHERE p.tipo = :tipo ORDER BY p.ano DESC, p.numero DESC")
    List<Projeto> findLatestByTipoJpql(@Param("tipo") TipoProposicao tipo, Pageable pageable);
    
    // Encontra o número máximo de projeto por tipo e ano
    @Query("SELECT MAX(p.numero) FROM Projeto p WHERE p.tipo = :tipo AND p.ano = :ano")
    Integer findMaxNumeroByTipoAndAno(@Param("tipo") TipoProposicao tipo, @Param("ano") Integer ano);
    
    // Encontra números faltantes na sequência de projetos
    @Query("SELECT p.numero FROM Projeto p WHERE p.tipo = :tipo AND p.ano = :ano ORDER BY p.numero")
    List<Integer> findAllNumerosByTipoAndAno(@Param("tipo") TipoProposicao tipo, @Param("ano") Integer ano);
    
    // Encontra projetos sem palavras-chave
    @Query("SELECT p FROM Projeto p WHERE p.palavrasChave IS NULL OR p.palavrasChave = ''")
    List<Projeto> findProjetosWithoutPalavrasChave(Pageable pageable);
    
    // Método Upsert (INSERT ... ON CONFLICT ... DO UPDATE) para evitar conflitos de ID
    @Modifying
    @Transactional
    @Query(value = 
        "INSERT INTO projetos (tipo, numero, ano, autor, autor_search, ementa, palavras_chave) " +
        "VALUES (CAST(:#{#projeto.tipo.name()} AS tp_proposicao), :#{#projeto.numero}, :#{#projeto.ano}, " +
        ":#{#projeto.autor}, :#{#projeto.autorSearch}, :#{#projeto.ementa}, :#{#projeto.palavrasChave}) " +
        "ON CONFLICT (tipo, numero, ano) DO UPDATE SET " +
        "autor = EXCLUDED.autor, " +
        "autor_search = EXCLUDED.autor_search, " +
        "ementa = EXCLUDED.ementa, " +
        "palavras_chave = EXCLUDED.palavras_chave " +
        "RETURNING id", nativeQuery = true)
    Integer upsertProjeto(@Param("projeto") Projeto projeto);
    
    // Método Upsert em lote para melhor performance
    @Modifying
    @Transactional
    @Query(value = 
        "INSERT INTO projetos (tipo, numero, ano, autor, autor_search, ementa, palavras_chave) " +
        "VALUES (CAST(:tipo AS tp_proposicao), :numero, :ano, :autor, :autorSearch, :ementa, :palavrasChave) " +
        "ON CONFLICT (tipo, numero, ano) DO UPDATE SET " +
        "autor = EXCLUDED.autor, " +
        "autor_search = EXCLUDED.autor_search, " +
        "ementa = EXCLUDED.ementa, " +
        "palavras_chave = EXCLUDED.palavras_chave", 
        nativeQuery = true)
    void upsertProjetoBatch(
        @Param("tipo") String tipo,
        @Param("numero") Integer numero,
        @Param("ano") Integer ano,
        @Param("autor") String autor,
        @Param("autorSearch") String autorSearch,
        @Param("ementa") String ementa,
        @Param("palavrasChave") String palavrasChave);
    
    // Busca um projeto por tipo, número e ano
    Projeto findByTipoAndNumeroAndAno(TipoProposicao tipo, Integer numero, Integer ano);
    
    // Conta o número de registros duplicados
    @Query(value = 
        "SELECT COUNT(*) FROM (" +
        "   SELECT tipo, numero, ano, COUNT(*) " +
        "   FROM projetos " +
        "   GROUP BY tipo, numero, ano " +
        "   HAVING COUNT(*) > 1" +
        ") AS duplicates", nativeQuery = true)
    Integer countDuplicates();
    
    // Remove duplicatas mantendo apenas o registro com menor ID para cada combinação tipo/numero/ano
    @Modifying
    @Transactional
    @Query(value = 
        "DELETE FROM projetos WHERE id IN (" +
        "   SELECT id FROM (" +
        "       SELECT id, " +
        "           ROW_NUMBER() OVER (PARTITION BY tipo, numero, ano ORDER BY id) as rn " +
        "       FROM projetos" +
        "   ) t " +
        "   WHERE t.rn > 1" +
        ")", nativeQuery = true)
    void removeDuplicates();
}