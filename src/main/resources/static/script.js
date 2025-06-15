// Este evento garante que todo o código dentro dele só será executado
// depois que todos os elementos HTML da página forem carregados e estiverem prontos.
document.addEventListener('DOMContentLoaded', () => {

    console.log("Página carregada. O script está sendo executado.");

    // Pega referências aos elementos do HTML
    const searchForm = document.getElementById('search-form');
    const searchInput = document.getElementById('search-input');
    const resultsContainer = document.getElementById('results-container');
    const loadingIndicator = document.getElementById('loading');
    const errorMessageDiv = document.getElementById('error-message');

    // Ponto de verificação: O formulário foi encontrado?
    if (!searchForm) {
        console.error("ERRO CRÍTICO: O formulário com id 'search-form' não foi encontrado no HTML.");
        return; // Para a execução se o elemento principal não for encontrado.
    }
    console.log("Formulário de busca encontrado com sucesso:", searchForm);


    // Adiciona o "ouvinte" ao formulário
    searchForm.addEventListener('submit', async (event) => {
        // Impede o comportamento padrão de recarregar a página
        event.preventDefault();

        const query = searchInput.value.trim();
        console.log("Busca iniciada para a query:", query);

        if (!query) return;

        const searchMode = document.querySelector('input[name="search-mode"]:checked').value;
        const apiUrl = `/api/search?q=${encodeURIComponent(query)}&mode=${searchMode}`;

        console.log("Chamando a API em:", apiUrl);

        // Prepara a interface para a busca
        resultsContainer.innerHTML = '';
        errorMessageDiv.classList.add('hidden');
        loadingIndicator.classList.remove('hidden');

        try {
            const response = await fetch(apiUrl);

            if (!response.ok) {
                throw new Error(`Erro na rede: ${response.status} - ${response.statusText}`);
            }

            const data = await response.json();
            console.log("Dados recebidos da API:", data);

            displayResults(data);

        } catch (error) {
            console.error('Falha ao buscar dados:', error);
            displayError('Ocorreu um erro ao realizar a busca.');
        } finally {
            console.log("Busca finalizada. Escondendo o 'loading'.");
            loadingIndicator.classList.add('hidden');
        }
    });



    function displayResults(projetos) {
        if (!projetos || projetos.length === 0) {
            console.log("Nenhum resultado para exibir.");
            displayError('Nenhum resultado encontrado para sua busca.');
            return;
        }

        console.log(`Exibindo ${projetos.length} resultados.`);
        projetos.forEach(projeto => {
            const card = document.createElement('div');
            card.className = 'result-card';

            let keywordsHtml = '';
            if (projeto.palavrasChave && projeto.palavrasChave.trim() !== '') {
                const keywordsArray = projeto.palavrasChave.split('|').map(k => k.trim().toLowerCase());
                keywordsHtml = `<div class="keywords-container">${keywordsArray.map(keyword => `<span class="keyword-tag">${keyword}</span>`).join('')}</div>`;
            }

            card.innerHTML = `
                <h3>${projeto.tipo || 'PROJETO'} ${projeto.numero}/${projeto.ano}</h3>
                <p class="meta">Autor(es): ${projeto.autor || 'Não informado'}</p>
                <p>${projeto.ementa}</p>
                ${keywordsHtml}
                <a href="${projeto.linkOficial}" target="_blank" rel="noopener noreferrer">Ver na íntegra no site da Câmara</a>
            `;
            resultsContainer.appendChild(card);
        });
    }

    function displayError(message) {
        errorMessageDiv.textContent = message;
        errorMessageDiv.classList.remove('hidden');
    }
});