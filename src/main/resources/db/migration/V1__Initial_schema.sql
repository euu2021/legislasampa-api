-- Criação do tipo ENUM
CREATE TYPE IF NOT EXISTS tp_proposicao AS ENUM ('PL', 'PDL', 'PLO', 'PR');

-- Tabela principal de projetos
CREATE TABLE IF NOT EXISTS projetos (
    id SERIAL PRIMARY KEY,
    tipo tp_proposicao NOT NULL,
    numero INTEGER NOT NULL,
    ano INTEGER NOT NULL,
    autor TEXT,
    autor_search TEXT,
    ementa TEXT,
    palavras_chave TEXT,
    embedding vector(384),
    CONSTRAINT uk_projeto_tipo_numero_ano UNIQUE (tipo, numero, ano)
);

-- Adicionar índice de texto para busca textual
CREATE INDEX IF NOT EXISTS idx_projetos_autor_search ON projetos USING gin(to_tsvector('portuguese', autor_search));
CREATE INDEX IF NOT EXISTS idx_projetos_ementa ON projetos USING gin(to_tsvector('portuguese', ementa));
CREATE INDEX IF NOT EXISTS idx_projetos_palavras_chave ON projetos USING gin(to_tsvector('portuguese', palavras_chave));

-- Adicionar índice para embedding (para busca por similaridade)
CREATE INDEX IF NOT EXISTS idx_projetos_embedding ON projetos USING ivfflat (embedding vector_cosine_ops)
WITH (lists = 100);

-- Adicionar índices para campos frequentemente consultados
CREATE INDEX IF NOT EXISTS idx_projetos_tipo ON projetos (tipo);
CREATE INDEX IF NOT EXISTS idx_projetos_ano ON projetos (ano);
CREATE INDEX IF NOT EXISTS idx_projetos_numero ON projetos (numero);