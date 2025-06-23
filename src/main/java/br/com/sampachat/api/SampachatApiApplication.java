package br.com.sampachat.api;

import br.com.sampachat.api.service.CargaDadosService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;


@SpringBootApplication
@EnableScheduling
public class SampachatApiApplication implements CommandLineRunner {

	@Autowired
	private CargaDadosService cargaDadosService;

	public static void main(String[] args) {
		SpringApplication.run(SampachatApiApplication.class, args);
	}

	@Override
	public void run(String... args) throws Exception {
		System.out.println(">>> SampaChat API iniciada. Verificando necessidade de carga de dados...");
		cargaDadosService.carregarDadosDaPlanilha(); // Primeiro, carrega da planilha
		cargaDadosService.gerarEmbeddings();      // Depois, gera os embeddings
		System.out.println(">>> Tarefas de inicialização concluídas.");
	}
}


//@SpringBootApplication
//public class SampachatApiApplication {
//	public static void main(String[] args) {
//		SpringApplication.run(SampachatApiApplication.class, args);
//	}
//}
//
