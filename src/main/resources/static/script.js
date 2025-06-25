// Este evento garante que todo o código dentro dele só será executado
// depois que todos os elementos HTML da página forem carregados e estiverem prontos.
document.addEventListener('DOMContentLoaded', () => {

    console.log("Página carregada. O script está sendo executado.");

    // Pega referências a TODOS os elementos do HTML que vamos manipular
    const searchForm = document.getElementById('search-form');
    const searchInput = document.getElementById('search-input');
    const resultsContainer = document.getElementById('results-container');
    const loadingIndicator = document.getElementById('loading');
    const cancelSearchButton = document.getElementById('cancel-search');
    const errorMessageDiv = document.getElementById('error-message');
    const filtersContainer = document.getElementById('filters-container');
    const chipsArea = document.getElementById('chips-area');
    const loadMoreButton = document.getElementById('load-more');
    const scrollSentinel = document.getElementById('scroll-sentinel');
    const endResultsMessage = document.getElementById('end-results-message');
    const searchExamples = document.getElementById('search-examples');

    // Estado global para paginação
    let currentPage = 0;
    let currentQuery = '';
    let hasMoreResults = false;
    let isLoading = false;
    let pageSize = null; // Será definido dinamicamente pela API
    
    // Variável para verificar se as configurações foram carregadas
    let configLoaded = false;
    
    // Variável para armazenar os filtros excluídos
    const excludedFilters = {
      Autor: [],
      Ano: [],
      Tipo: [],
      Número: []
    };
    
    // Função para limpar os filtros excluídos (chamar em nova busca manual)
    function resetExcludedFilters() {
      Object.keys(excludedFilters).forEach(key => {
        excludedFilters[key] = [];
      });
      console.log("Filtros excluídos foram resetados");
    }
    
    // Buscar configurações do servidor
    fetch('/api/config')
        .then(response => response.json())
        .then(config => {
            console.log("Configurações carregadas do servidor:", config);
            pageSize = config.defaultPageSize;
            configLoaded = true;
            
            // Se houver um envio de formulário pendente, execute-o agora
            if (pendingSearch) {
                executeSearch(pendingSearch.query, pendingSearch.page, pendingSearch.isNewSearch);
                pendingSearch = null;
            }
        })
        .catch(error => {
            console.error("Erro ao carregar configurações:", error);
            configLoaded = true; // Mesmo com erro, consideramos como carregado para não bloquear a interface
        });
        
    // Armazena uma busca pendente se as configurações ainda não foram carregadas
    let pendingSearch = null;

    if (!searchForm) {
        console.error("ERRO CRÍTICO: O formulário com id 'search-form' não foi encontrado no HTML.");
        return;
    }

    // O "ouvinte" do formulário agora chama a função de busca principal
    searchForm.addEventListener('submit', (event) => {
        event.preventDefault();
        console.log("Formulário de busca enviado");
        const query = searchInput.value.trim();
        if (query) {
            console.log("Query válida:", query);
            // Reset da paginação em nova busca
            currentPage = 0;
            currentQuery = query;
            
            // Reset de filtros excluídos em nova busca manual
            resetExcludedFilters();
            
            // Executar a busca
            executeSearch(query, currentPage, true);
        } else {
            console.log("Query vazia, não executando busca");
        }
    });

    // Adicionar evento para o botão "Carregar mais"
    loadMoreButton.addEventListener('click', () => {
        if (hasMoreResults && !isLoading) {
            currentPage++;
            executeSearch(currentQuery, currentPage, false);
        }
    });
    
    // Adicionar evento para o botão "Cancelar busca"
    cancelSearchButton.addEventListener('click', () => {
        cancelSearch();
    });

    // Configurar o Infinite Scroll com Intersection Observer
    const observer = new IntersectionObserver((entries) => {
        // Se o elemento sentinel está visível e temos mais resultados
        if (entries[0].isIntersecting && hasMoreResults && !isLoading) {
            currentPage++;
            executeSearch(currentQuery, currentPage, false);
        }
    }, { threshold: 1.0 }); // Threshold 1.0 significa que o elemento deve estar completamente visível

    // Observe o sentinel
    observer.observe(scrollSentinel);

    // ===================================================================
    // =========== FUNÇÃO DE BUSCA PRINCIPAL E LÓGICA DE CHIPS ===========
    // ===================================================================

    // Variável para armazenar a conexão EventSource atual
    let currentEventSource = null;
    
    // Função para cancelar a busca atual
    function cancelSearch() {
        if (currentEventSource) {
            console.log("Cancelando busca em andamento...");
            currentEventSource.close();
            currentEventSource = null;
            loadingIndicator.classList.add('hidden');
            isLoading = false;
        }
    }
    
    // Função central que executa a busca e coordena a exibição usando SSE
    function executeSearch(query, page, isNewSearch) {
        // Se as configurações ainda não foram carregadas, armazenamos a busca para execução posterior
        if (!configLoaded) {
            pendingSearch = { query, page, isNewSearch };
            console.log("Busca adiada até que as configurações sejam carregadas");
            return;
        }
        
        // Verificação de segurança caso o servidor não tenha retornado um valor válido
        if (pageSize === null) {
            console.error("ERRO: pageSize não definido. Usando valor padrão de 10.");
            pageSize = 10; // Valor de emergência caso o servidor não responda
        }
        
        // Cancelar busca anterior se existir
        cancelSearch();
        
        // Iniciar com uma URL básica (usando o endpoint de stream)
        let apiUrl = `/api/search/stream?q=${encodeURIComponent(query)}&page=${page}&size=${pageSize}`;
        
        // Adicionar filtros excluídos apenas se houver algum
        const hasExcludedFilters = Object.values(excludedFilters).some(arr => arr.length > 0);
        if (hasExcludedFilters) {
            const excludedFiltersJson = JSON.stringify(excludedFilters);
            apiUrl += `&excludedFilters=${encodeURIComponent(excludedFiltersJson)}`;
            console.log("Filtros excluídos:", excludedFilters);
        }
        
        console.log(`Executando busca com SSE. Tamanho de página: ${pageSize}`);
        console.log("URL da API (SSE):", apiUrl);

        // Prepara a interface para a busca
        if (isNewSearch) {
            resultsContainer.innerHTML = '';
            if (chipsArea.childElementCount === 0) {
                filtersContainer.classList.add('hidden');
            }
            endResultsMessage.classList.add('hidden');
            
            // Esconde a seção de exemplos após a primeira pesquisa
            if (searchExamples) {
                searchExamples.classList.add('hidden');
            }
        }
        
        errorMessageDiv.classList.add('hidden');
        loadingIndicator.classList.remove('hidden');
        loadingIndicator.querySelector('span').textContent = "Buscando...";
        loadMoreButton.classList.add('hidden');
        isLoading = true;

        // Criar o EventSource
        currentEventSource = new EventSource(apiUrl);
        
        // Processar eventos (resultados)
        currentEventSource.onmessage = function(event) {
            try {
                const data = JSON.parse(event.data);
                console.log(`Dados recebidos via SSE (tipo: ${data.resultType}):`, data);
                
                // Exibir filtros somente em nova busca e com o primeiro conjunto de resultados
                if (isNewSearch && data.appliedFilters) {
                    displayFilters(data.appliedFilters, query);
                }
                
                // Se são resultados exatos (primeira etapa)
                if (data.resultType === 'exact') {
                    // Exibir resultados iniciais
                    displayResults(data, query, isNewSearch);
                    
                    // Atualizar mensagem de carregamento para mostrar que estamos buscando resultados semânticos
                    loadingIndicator.querySelector('span').textContent = "Buscando resultados semânticos...";
                    
                    // Atualizar estado de paginação temporariamente
                    hasMoreResults = data.hasMore;
                }
                
                // Se são resultados completos (inclui resultados semânticos)
                if (data.resultType === 'complete') {
                    // Limpar resultados anteriores (se nova busca ou se são resultados exatos a serem substituídos)
                    if (isNewSearch) {
                        resultsContainer.innerHTML = '';
                    }
                    
                    // Exibir resultados completos
                    displayResults(data, query, isNewSearch);
                    
                    // Atualizar estado de paginação final
                    hasMoreResults = data.hasMore;
                    
                    // Finalizar busca
                    loadingIndicator.classList.add('hidden');
                    isLoading = false;
                    
                    // Mostrar ou esconder o botão "Carregar mais"
                    loadMoreButton.classList.toggle('hidden', !hasMoreResults);
                    
                    // Mostrar mensagem de fim dos resultados se não houver mais
                    const hasResults = document.querySelectorAll('.result-card').length > 0;
                    endResultsMessage.classList.toggle('hidden', hasMoreResults || !hasResults);
                    
                    // Fechar a conexão SSE
                    currentEventSource.close();
                    currentEventSource = null;
                }
                
                // Se houve um erro
                if (data.resultType === 'error') {
                    displayError('Ocorreu um erro ao realizar a busca.');
                    loadingIndicator.classList.add('hidden');
                    isLoading = false;
                    
                    // Fechar a conexão SSE
                    currentEventSource.close();
                    currentEventSource = null;
                }
                
            } catch (error) {
                console.error('Erro ao processar dados do SSE:', error);
                displayError('Ocorreu um erro ao processar os resultados.');
                loadingIndicator.classList.add('hidden');
                isLoading = false;
                
                // Fechar a conexão SSE
                if (currentEventSource) {
                    currentEventSource.close();
                    currentEventSource = null;
                }
            }
        };
        
        // Tratar erros de conexão
        currentEventSource.onerror = function(error) {
            console.error('Erro no EventSource:', error);
            displayError('Erro de conexão. Tente novamente.');
            loadingIndicator.classList.add('hidden');
            isLoading = false;
            
            // Fechar a conexão SSE
            currentEventSource.close();
            currentEventSource = null;
        };
    }

    // NOVA FUNÇÃO para criar e exibir os chips de filtro organizados por categoria
    function displayFilters(filters, originalQuery) {
      chipsArea.innerHTML = '';            // limpa chips antigos
      if (!filters || Object.keys(filters).length === 0) {
        filtersContainer.classList.add('hidden');
        return;
      }
      filtersContainer.classList.remove('hidden');

      // Para cada categoria de filtros
      for (const category in filters) {
        if (filters[category].length === 0) continue;
        
        // Filtrar os valores para remover aqueles que o usuário excluiu
        const filteredValues = filters[category].filter(value => 
          !excludedFilters[category] || !excludedFilters[category].includes(value)
        );
        
        // Se não houver valores após filtrar, pular esta categoria
        if (filteredValues.length === 0) continue;
        
        // Criar um grupo de categoria
        const categoryGroup = document.createElement('div');
        categoryGroup.className = 'category-group';
        
        // Adicionar o rótulo da categoria
        const categoryLabel = document.createElement('div');
        categoryLabel.className = 'category-label';
        categoryLabel.textContent = `${category}:`;
        categoryGroup.appendChild(categoryLabel);
        
        // Criar o contêiner para os chips desta categoria
        const chipsContainer = document.createElement('div');
        chipsContainer.className = 'category-chips';
        
        // Adicionar cada chip da categoria (filtrado) com separador "ou"
        filteredValues.forEach((value, index) => {
          // Adicionar o chip
          const chip = document.createElement('div');
          chip.className = 'chip';
          chip.dataset.category = category;
          chip.dataset.value = value;
          chip.textContent = value;
          
          // Adicionar botão de remover
          const closeBtn = document.createElement('span');
          closeBtn.className = 'chip-close';
          closeBtn.innerHTML = '&times;';
          closeBtn.addEventListener('click', (e) => {
            e.stopPropagation(); // Impedir propagação do clique
            
            // Adicionar à lista de filtros excluídos
            if (!excludedFilters[category]) {
              excludedFilters[category] = [];
            }
            
            excludedFilters[category].push(value);
            console.log(`Filtro excluído: ${category} = ${value}`);
            console.log('Filtros excluídos:', excludedFilters);
            
            // Remover o chip visualmente
            chip.remove();
            
            // Se há um separador antes deste chip, remova-o
            if (chip.previousElementSibling && chip.previousElementSibling.classList.contains('chip-separator')) {
              chip.previousElementSibling.remove();
            } 
            // Se há um separador depois deste chip, remova-o
            else if (chip.nextElementSibling && chip.nextElementSibling.classList.contains('chip-separator')) {
              chip.nextElementSibling.remove();
            }
            
            // Verificar se ainda há chips nesta categoria
            if (chipsContainer.querySelectorAll('.chip').length === 0) {
              categoryGroup.remove();
            }
            
            // Se não houver mais chips, esconde o container de filtros
            if (chipsArea.childElementCount === 0) {
              filtersContainer.classList.add('hidden');
            }
            
            // Fazer uma nova busca com a query atual
            executeSearch(currentQuery, 0, true);
          });
          
          chip.appendChild(closeBtn);
          chipsContainer.appendChild(chip);
          
          // Adicionar o separador "ou" após cada chip (exceto o último)
          if (index < filteredValues.length - 1) {
            const separator = document.createElement('span');
            separator.className = 'chip-separator';
            separator.textContent = 'ou';
            chipsContainer.appendChild(separator);
          }
        });
        
        categoryGroup.appendChild(chipsContainer);
        chipsArea.appendChild(categoryGroup);
      }
    }

    // ===================================================================
    // =========== SUAS FUNÇÕES ORIGINAIS (COM PEQUENOS AJUSTES) =========
    // ===================================================================

    function displayResults(data, query, isNewSearch) {
        // Use os termos de destaque enviados pelo backend, se disponíveis
        // Caso contrário, usa o método original com a query
        const highlighter = data.highlightTerms && data.highlightTerms.length > 0 ? 
                          makeHighlighterFromTerms(data.highlightTerms) : 
                          makeHighlighter(query);

        // Extrair os projetos do objeto de resposta
        const projetos = data.projetos;

        if (isNewSearch) {
            resultsContainer.innerHTML = '';
        }

        if (!projetos || projetos.length === 0) {
            if (isNewSearch) {
                if (chipsArea.childElementCount > 0) {
                    // Se temos chips, é ok não ter resultados. Apenas não mostre o erro.
                } else {
                    displayError('Nenhum resultado encontrado para sua busca.');
                }
            } else {
                // Se estamos carregando mais e não há resultados
                const hasResults = document.querySelectorAll('.result-card').length > 0;
                if (hasResults) {
                    // Se já temos resultados, mostrar mensagem de fim
                    endResultsMessage.classList.remove('hidden');
                } else {
                    displayError('Não há mais resultados disponíveis.');
                }
            }
            return;
        }

        // Calcular o índice inicial com base na página
        const startIndex = isNewSearch ? 0 : document.querySelectorAll('.result-card').length;

        projetos.forEach((projeto, index) => {
            const card = document.createElement('div');
            card.className = 'result-card';
            
            // Índice global do resultado (considerando paginação)
            const globalIndex = startIndex + index + 1;

            let keywordsHtml = '';
            if (projeto.palavrasChave && projeto.palavrasChave.trim() !== '') {
                const keywordsArray = projeto.palavrasChave.split('|').map(k => k.trim().toLowerCase());
                keywordsHtml = `<div class="keywords-container">${keywordsArray.map(k => `<span class="keyword-tag">${highlighter(k)}</span>`).join('')}</div>`;
            }

            card.innerHTML = `
                <h3>
                    <span>${highlighter(`${globalIndex}. ${projeto.tipo || 'PROJETO'} ${projeto.numero}/${projeto.ano}`)}</span>
                    <a class="pdf-link"
                       href="${projeto.linkPdf}"
                       target="_blank"
                       rel="noopener noreferrer">
                       Ver Proposição Inicial ↗
                    </a>
                </h3>
                <p class="meta">Autor(es): ${highlighter(projeto.autor || 'Não informado')}</p>
                <p>${highlighter(projeto.ementa)}</p>
                ${keywordsHtml}
                <div class="links-container">
                    <a class="portal-link"
                       href="${projeto.linkPortal}"
                       target="_blank"
                       rel="noopener noreferrer">
                       Ver no Portal ↗
                    </a>
                    <a class="splegis-link"
                       href="${projeto.linkSpLegis}"
                       target="_blank"
                       rel="noopener noreferrer">
                       Ver no SPLegis ↗
                    </a>
                </div>
            `;
            resultsContainer.appendChild(card);
        });
        
        // Atualizar a posição do sentinel após adicionar novos resultados
        scrollSentinel.style.height = '20px';
        scrollSentinel.style.marginTop = '20px';
    }

    function displayError(message) {
        errorMessageDiv.textContent = message;
        errorMessageDiv.classList.remove('hidden');
    }

    function escapeRegExp(str) {
        return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    }

    // Função para criar highlighter a partir de termos específicos enviados pelo backend
    function makeHighlighterFromTerms(terms) {
      const strip = s => s.normalize('NFD').replace(/[\u0300-\u036f]/g, '');
      
      // Filtra os termos, remove duplicatas e prepara para a regex
      const filteredTerms = terms
          .filter(t => t && t.length > 1)
          .map(t => strip(t))
          .map(escapeRegExp);
      
      if (!filteredTerms.length) return text => text;
      
      // Cria a regex com todos os termos
      const regex = new RegExp(`(${filteredTerms.join('|')})`, 'gi');
      
      return rawText => {
        const plain = strip(rawText);
        let result = '';
        let last = 0;
        plain.replace(regex, (match, _grp, offset) => {
          result += rawText.slice(last, offset);
          result += '<mark>' + rawText.slice(offset, offset + match.length) + '</mark>';
          last = offset + match.length;
          return match;
        });
        result += rawText.slice(last);
        return result;
      };
    }

    // Sua excelente função makeHighlighter continua exatamente igual
    function makeHighlighter(query) {
      const strip = s => s.normalize('NFD').replace(/[\u0300-\u036f]/g, '');
      const terms = strip(query)
          .split(/\s+/)
          .filter(t => t.length > 1)
          .map(escapeRegExp);
      if (!terms.length) return text => text;
      const regex = new RegExp(`(${terms.join('|')})`, 'gi');
      return rawText => {
        const plain = strip(rawText);
        let result = '';
        let last = 0;
        plain.replace(regex, (match, _grp, offset) => {
          result += rawText.slice(last, offset);
          result += '<mark>' + rawText.slice(offset, offset + match.length) + '</mark>';
          last = offset + match.length;
          return match;
        });
        return result + rawText.slice(last);
      };
    }

});