package com.forgeddetect.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * The slice of HashiCorp Vault's Transit engine the report signer uses (ed25519 keys only):
 * read / create / rotate a key and sign. Private keys stay in this "Vault".
 */
public final class FakeVaultTransit implements AutoCloseable {

    public static final String TOKEN = "test-root-token";

    private final HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final List<KeyPair> versions = new ArrayList<>();
    private final List<Instant> created = new ArrayList<>();
    private final String keyName;
    private boolean exists;

    public FakeVaultTransit(String keyName) throws IOException {
        this.keyName = keyName;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/transit/", this::handle);
        server.start();
    }

    public String address() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public synchronized int latestVersion() {
        return versions.size();
    }

    private synchronized void handle(HttpExchange ex) throws IOException {
        try (ex) {
            if (!TOKEN.equals(ex.getRequestHeaders().getFirst("X-Vault-Token"))) {
                send(ex, 403, json.createObjectNode().put("errors", "permission denied"));
                return;
            }
            String path = ex.getRequestURI().getPath().substring("/v1/transit/".length());
            String method = ex.getRequestMethod();
            if (path.equals("keys/" + keyName) && method.equals("GET")) {
                if (!exists) {
                    send(ex, 404, json.createObjectNode());
                    return;
                }
                send(ex, 200, keyBody());
            } else if (path.equals("keys/" + keyName) && method.equals("POST")) {
                JsonNode req = json.readTree(ex.getRequestBody());
                if (!"ed25519".equals(req.path("type").asText())) {
                    send(ex, 400, json.createObjectNode().put("errors", "unsupported type"));
                    return;
                }
                exists = true;
                addVersion();
                send(ex, 204, null);
            } else if (path.equals("keys/" + keyName + "/rotate") && method.equals("POST")) {
                addVersion();
                send(ex, 200, keyBody());
            } else if (path.equals("sign/" + keyName) && method.equals("POST")) {
                byte[] input = Base64.getDecoder().decode(json.readTree(ex.getRequestBody()).path("input").asText());
                Signature s = Signature.getInstance("Ed25519");
                s.initSign(versions.get(versions.size() - 1).getPrivate());
                s.update(input);
                ObjectNode data = json.createObjectNode();
                data.put("signature", "vault:v" + versions.size() + ":" + Base64.getEncoder().encodeToString(s.sign()));
                data.put("key_version", versions.size());
                send(ex, 200, json.createObjectNode().set("data", data));
            } else {
                send(ex, 404, json.createObjectNode());
            }
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private void addVersion() throws Exception {
        versions.add(KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        created.add(Instant.now());
    }

    private ObjectNode keyBody() {
        ObjectNode data = json.createObjectNode();
        data.put("type", "ed25519");
        data.put("latest_version", versions.size());
        ObjectNode keys = data.putObject("keys");
        for (int i = 0; i < versions.size(); i++) {
            byte[] der = versions.get(i).getPublic().getEncoded();
            byte[] raw = Arrays.copyOfRange(der, der.length - 32, der.length);
            keys.putObject(String.valueOf(i + 1))
                    .put("public_key", Base64.getEncoder().encodeToString(raw))
                    .put("creation_time", created.get(i).toString())
                    .put("name", "ed25519");
        }
        return json.createObjectNode().set("data", data);
    }

    private void send(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : json.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            ex.getResponseBody().write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
