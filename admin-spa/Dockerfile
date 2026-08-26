FROM node:22-alpine AS build
WORKDIR /app
# `npm ci` (not `npm install`): the admin EC2 box builds this image from a plain
# clone at deploy time, so a floating semver resolve would drift between deploys
# and depends on the registry being healthy mid-deploy. The lock is tracked via a
# .gitignore negation, matching frontend/.
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
# Per-environment cutover for the COGS-vs-charges table. Empty (default) = show all
# books (fresh account / prod); a QA date hides pre-feature books. Vite exposes
# VITE_-prefixed env at build time via import.meta.env.
ARG VITE_CHARGES_CUTOVER=""
ENV VITE_CHARGES_CUTOVER=$VITE_CHARGES_CUTOVER
ARG VITE_ENABLE_REFUND_QUEUE="true"
ENV VITE_ENABLE_REFUND_QUEUE=$VITE_ENABLE_REFUND_QUEUE
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
