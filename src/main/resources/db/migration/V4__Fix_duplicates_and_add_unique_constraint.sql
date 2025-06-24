-- Identificar e remover duplicatas de maneira mais robusta
-- Primeiro criamos uma tabela temporária com os registros únicos que queremos manter
CREATE TEMPORARY TABLE projetos_unique AS
    SELECT DISTINCT ON (tipo, numero, ano) 
        id, tipo, numero, ano, autor, autor_search, ementa, palavras_chave, embedding
    FROM projetos
    ORDER BY tipo, numero, ano, id;

-- Salvamos o count para log
DO $$
DECLARE
    total_count INTEGER;
    unique_count INTEGER;
    duplicate_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO total_count FROM projetos;
    SELECT COUNT(*) INTO unique_count FROM projetos_unique;
    duplicate_count := total_count - unique_count;
    
    RAISE NOTICE 'Total de registros: %, Registros únicos: %, Duplicatas: %', 
        total_count, unique_count, duplicate_count;
END $$;

-- Agora excluímos todos os registros da tabela original
DELETE FROM projetos;

-- Reinsere apenas os registros únicos
INSERT INTO projetos (id, tipo, numero, ano, autor, autor_search, ementa, palavras_chave, embedding)
    SELECT id, tipo, numero, ano, autor, autor_search, ementa, palavras_chave, embedding
    FROM projetos_unique;

-- Adiciona a restrição única
ALTER TABLE projetos ADD CONSTRAINT uk_projetos_tipo_numero_ano UNIQUE (tipo, numero, ano);

-- Reseta a sequência de id para o próximo valor após o maior id existente
SELECT setval('projetos_id_seq', (SELECT COALESCE(MAX(id), 0) FROM projetos), true);

-- Limpa a tabela temporária
DROP TABLE projetos_unique;