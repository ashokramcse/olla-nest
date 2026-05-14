FROM node:24-alpine

WORKDIR /app

ENV NODE_ENV=production
ENV OLLA_NEST_DOCKER_RUNTIME=true

COPY package*.json ./
RUN npm ci --omit=dev

COPY . .

EXPOSE 3000

CMD ["npm", "run", "container:start"]
