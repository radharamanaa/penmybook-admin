FROM node:22-alpine AS build
WORKDIR /app
COPY package.json ./
RUN npm install
COPY . .
# Per-environment cutover for the COGS-vs-charges table. Empty (default) = show all
# books (fresh account / prod); a QA date hides pre-feature books. Vite exposes
# VITE_-prefixed env at build time via import.meta.env.
ARG VITE_CHARGES_CUTOVER=""
ENV VITE_CHARGES_CUTOVER=$VITE_CHARGES_CUTOVER
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
