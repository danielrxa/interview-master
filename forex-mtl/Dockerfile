ARG SBT_IMAGE=sbtscala/scala-sbt:latest

FROM ${SBT_IMAGE} AS build

WORKDIR /app

COPY forex-mtl/project ./project
COPY forex-mtl/build.sbt ./
COPY forex-mtl/src ./src

RUN sbt clean stage

FROM eclipse-temurin:17-jre-jammy

WORKDIR /opt/forex

RUN useradd --system --create-home --home-dir /opt/forex app

COPY --from=build /app/target/universal/stage ./

RUN chown -R app:app /opt/forex

USER app

EXPOSE 8081

ENTRYPOINT ["./bin/forex"]
