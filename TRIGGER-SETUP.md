# Configuração do Trigger Externo para Sincronização de Dados

Este documento descreve como configurar o trigger externo de sincronização para o LegislaSampa usando o serviço [cron-job.org](https://cron-job.org).

## Visão Geral

Em vez de confiar no agendador interno do Spring, que pode ser afetado pelo comportamento de "sleep" do Render.com, utilizamos um serviço externo (cron-job.org) para fazer chamadas HTTP programadas para nosso endpoint de sincronização.

## Endpoints Disponíveis

### 1. Verificação de Saúde
- **URL**: `https://legislasampa-api.onrender.com/health`
- **URL Alternativa**: `https://legislasampa-api.onrender.com/monitor/health`
- **Método**: GET
- **Descrição**: Verifica se a API está funcionando corretamente.
- **Resposta**: 
  ```
  OK - Sistema saudável
  ```
  ou
  ```
  ALERTA - [número] falhas consecutivas de sincronização. Última falha: [mensagem]
  ```

### 2. Trigger de Sincronização
- **URL**: `https://legislasampa-api.onrender.com/api/admin/sync`
- **Método**: POST
- **Cabeçalhos**:
  - `X-API-Key`: `L3g1sl4S4mp4-2023-S3cr3tK3y-V1` (ou o valor configurado em `app.admin.secret-key`)
- **Descrição**: Inicia o processo de sincronização de dados.
- **Resposta de Sucesso**:
  ```json
  {
    "success": true,
    "message": "Sincronização concluída com sucesso",
    "timestamp": "2025-06-23T14:30:00"
  }
  ```
- **Resposta de Erro**:
  ```json
  {
    "success": false,
    "message": "Erro durante sincronização: [detalhes do erro]",
    "timestamp": "2025-06-23T14:30:00"
  }
  ```

### 3. Trigger de Verificação de Integridade
- **URL**: `https://legislasampa-api.onrender.com/api/admin/integrity`
- **Método**: POST
- **Cabeçalhos**:
  - `X-API-Key`: `L3g1sl4S4mp4-2023-S3cr3tK3y-V1` (ou o valor configurado em `app.admin.secret-key`)
- **Descrição**: Inicia o processo de verificação de integridade dos dados.
- **Resposta de Sucesso**:
  ```json
  {
    "success": true,
    "message": "Verificação de integridade concluída com sucesso",
    "timestamp": "2025-06-23T14:30:00"
  }
  ```
- **Resposta de Erro**:
  ```json
  {
    "success": false,
    "message": "Erro durante verificação de integridade: [detalhes do erro]",
    "timestamp": "2025-06-23T14:30:00"
  }
  ```

## Configuração no cron-job.org

1. **Criar uma conta** no [cron-job.org](https://cron-job.org)

2. **Adicionar o Job de Sincronização**:
   - **Título**: LegislaSampa Sync
   - **URL**: `https://legislasampa-api.onrender.com/api/admin/sync`
   - **Método**: POST
   - **Cabeçalhos personalizados**:
     - Nome: `X-API-Key`
     - Valor: `L3g1sl4S4mp4-2023-S3cr3tK3y-V1` (a mesma configurada em `app.admin.secret-key`)
   - **Programação**: Definir para o horário desejado (ex: 11:33 AM)
   - **Fuso horário**: Selecione "America/Sao_Paulo" (ou UTC-3)
   - **Opções de notificação**: Configurar para notificar por email em caso de falha

3. **Adicionar o Job de Verificação de Integridade**:
   - **Título**: LegislaSampa Integrity Check
   - **URL**: `https://legislasampa-api.onrender.com/api/admin/integrity`
   - **Método**: POST
   - **Cabeçalhos personalizados**:
     - Nome: `X-API-Key`
     - Valor: `L3g1sl4S4mp4-2023-S3cr3tK3y-V1` (a mesma configurada em `app.admin.secret-key`)
   - **Programação**: Definir para 4:00 AM (ou o horário desejado)
   - **Fuso horário**: Selecione "America/Sao_Paulo" (ou UTC-3)
   - **Opções de notificação**: Configurar para notificar por email em caso de falha

4. **Configurar o Job de Monitoramento** (opcional):
   - **Título**: LegislaSampa Health Check
   - **URL**: `https://legislasampa-api.onrender.com/health` (ou use `/monitor/health` para mais detalhes)
   - **Método**: GET
   - **Programação**: A cada 5 minutos
   - **Opções de notificação**: Configurar para notificar por email em caso de falha

## Segurança

- A chave de API (`X-API-Key`) é necessária para acessar o endpoint de sincronização
- Altere a chave de API no arquivo `application.properties` se necessário
- A comunicação é feita via HTTPS para proteger a transmissão da chave
- O endpoint tem configuração CORS para permitir apenas origens específicas

## Solução de Problemas

Se a sincronização falhar:

1. Verifique os logs da aplicação para detalhes do erro
2. Verifique se a chave de API está correta
3. Verifique se o serviço no Render.com está online
4. Verifique se há limitações de recursos no Render.com que possam estar afetando a sincronização
