package br.com.sampachat.api.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

// Mapeamento para o ENUM do PostgreSQL
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.Type;
import jakarta.persistence.Convert;




@Getter
@Setter
@Entity
@Table(name = "projetos") // Liga esta classe à tabela "projetos"
public class Projeto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    // Mapeando o ENUM customizado do PostgreSQL
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", columnDefinition = "tp_proposicao")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM) // Dica para o Hibernate sobre o tipo
    private TipoProposicao tipo;

    private Integer numero;
    private Integer ano;
    
    @Column(columnDefinition = "TEXT")
    private String autor;

    @Column(name = "autor_search", columnDefinition = "TEXT")
    private String autorSearch;

    @Column(columnDefinition = "TEXT")
    private String ementa;

    @Column(name = "palavras_chave", columnDefinition = "TEXT")
    private String palavrasChave;

    // Não vamos mapear o ts_vector, pois ele é gerado pelo banco

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = 384)
    //    @Column(columnDefinition = "vector(384)")
//    @Column(columnDefinition = "vector")
    @Column(name = "embedding", columnDefinition = "vector(384)")
    private float[] embedding;



    // ... outros campos como data e status podem ser adicionados aqui
}