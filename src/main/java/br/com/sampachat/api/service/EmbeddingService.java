package br.com.sampachat.api.service;

import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;

// Nenhum import da ProgressBar é mais necessário

@Service
public class EmbeddingService {

    private Predictor<String[], float[][]> predictor;
    private ZooModel<String[], float[][]> model;

    @PostConstruct
    public void init() throws Exception {
        System.out.println("Carregando modelo de embedding... Isso pode levar alguns minutos na primeira vez.");

        // Criteria final, sem a barra de progresso que causava o erro.
        Criteria<String[], float[][]> criteria = Criteria.builder()
                .setTypes(String[].class, float[][].class)
                .optEngine("PyTorch")
                .optOption("from_djl_repository", "true")
                .optArtifactId("sentence-transformers/all-MiniLM-L6-v2")
                // A LINHA .optProgress FOI REMOVIDA DAQUI
                .build();

        this.model = criteria.loadModel();
        this.predictor = model.newPredictor();

        System.out.println("Modelo de embedding carregado com sucesso.");
    }

    public float[][] generateEmbeddings(List<String> texts) throws TranslateException {
        if (texts == null || texts.isEmpty()) {
            return new float[0][];
        }
        return predictor.predict(texts.toArray(new String[0]));
    }

    @PreDestroy
    public void destroy() {
        if (predictor != null) {
            predictor.close();
        }
        if (model != null) {
            model.close();
        }
    }
}