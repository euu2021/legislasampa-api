-- Marcação de baseline para começar a rastrear as migrações
INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
SELECT 1, '1', 'Initial schema', 'SQL', 'V1__Initial_schema.sql', 0, current_user, 0, true
WHERE NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '1');