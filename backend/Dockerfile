FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY cmd/ ./cmd/
COPY internal/ ./internal/
RUN CGO_ENABLED=0 go build -trimpath -o /out/varde ./cmd/varde

FROM alpine:3.23
RUN addgroup -g 10001 varde \
    && adduser -D -H -u 10001 -G varde varde \
    && mkdir /data \
    && chown varde:varde /data \
    && chmod 0700 /data
COPY --from=build /out/varde /usr/local/bin/varde
USER 10001:10001
ENTRYPOINT ["/usr/local/bin/varde", "-db", "/data/varde.sqlite"]
CMD ["serve", "-listen", "127.0.0.1:8080"]
