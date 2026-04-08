# Vinlien

Open Source selfhosted music streaming application

## Features

- Stream your music collection from anywhere
- Get recommendations from multiple providers (lastfm, youtube (via invidious), soundcloud, ...)
- User authentication and access control
- Mobile-friendly interface

## Installation

The simplest way to install Vinlien is to use Docker. You can find the Docker image on ghcr: https://ghcr.io/0x1bd/vinlien

This project contains a [docker-compose](docker-compose.yml) file that can be used to easily set up the application.

## LLM disclosure

I partially am using LLM's to write this project, particularly for the frontend.
The backend is mostly written by me, with some help from LLM's for specific tasks (e.g. writing tests, or generating
code for specific features).