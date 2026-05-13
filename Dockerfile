FROM node:24-alpine

WORKDIR /app

ENV NODE_ENV=production
ENV STORAGE_MODE=production

COPY package*.json ./
RUN npm ci

COPY . .
RUN npm run build
RUN npm prune --omit=dev

EXPOSE 3000

CMD ["npm", "start"]
