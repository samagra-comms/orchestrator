![Maven Build](https://github.com/samagra-comms/orchestrator/actions/workflows/build.yml/badge.svg)
![Docker Build](https://github.com/samagra-comms/orchestrator/actions/workflows/docker-build-push.yml/badge.svg)

# Overview
Orchestrator authenticates & processes the user data from whom the message is received. The XMessage will then be pushed to the kafka topic, the transformer will listen to this topic to further process it.
