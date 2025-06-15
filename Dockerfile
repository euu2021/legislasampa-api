# Estágio 1: Build - Usamos uma imagem do Maven que já tem o JDK para compilar nosso projeto
FROM maven:3.9-eclipse-temurin-17-focal AS build

# Define o diretório de trabalho dentro da imagem
WORKDIR /app

# Copia o pom.xml primeiro para aproveitar o cache de camadas do Docker
COPY pom.xml .

# Baixa as dependências (isso só será refeito se o pom.xml mudar)
RUN mvn dependency:go-offline

# Copia o resto do código do nosso projeto
COPY src ./src

# Compila o projeto e cria o arquivo JAR, pulando os testes
RUN mvn package -Dmaven.test.skip=true

# Estágio 2: Run - Usamos uma imagem base leve que só tem o Java para rodar o app
FROM eclipse-temurin:17-jre-focal

WORKDIR /app

# Copia o arquivo JAR que foi criado no estágio de build
COPY --from=build /app/target/sampachat-api-0.0.1-SNAPSHOT.jar ./app.jar

# Expõe a porta 8080 para o mundo exterior
EXPOSE 8080

# O comando que será executado quando o contêiner iniciar
ENTRYPOINT ["java", "-jar", "app.jar"]