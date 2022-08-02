![Maven Build](https://github.com/samagra-comms/orchestrator/actions/workflows/build.yml/badge.svg)
![Docker Build](https://github.com/samagra-comms/orchestrator/actions/workflows/docker-build-push.yml/badge.svg)

# Overview
Orchestrator authenticates & processes the user data from whom the message is received. The XMessage will then be pushed to the kafka topic, the transformer will listen to this topic to further process it.

# Getting Started

## Prerequisites

* java 11 or above
* docker
* kafka
* postgresql
* redis
* fusion auth
* lombok plugin for IDE
* maven

## Build
* build with tests run using command **mvn clean install -U**
* or build without tests run using command **mvn clean install -DskipTests**

# Detailed Documentation
[Click here](https://uci.sunbird.org/use/developer/uci-basics)