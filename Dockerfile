FROM node:24-alpine

WORKDIR /app

ENV NODE_ENV=production
ENV OLLA_NEST_DOCKER_RUNTIME=true

# Voice transcription: Python + ffmpeg + openai-whisper (tiny model ~75MB)
RUN apk add --no-cache python3 py3-pip ffmpeg build-base \
    && pip3 install --break-system-packages openai-whisper \
    && python3 -c "import whisper; whisper.load_model('tiny')" \
    && echo "Whisper ready"

COPY package*.json ./
RUN npm ci --omit=dev

COPY . .

EXPOSE 3000

CMD ["npm", "run", "container:start"]
