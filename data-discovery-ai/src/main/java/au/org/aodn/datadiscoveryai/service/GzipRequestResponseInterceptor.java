package au.org.aodn.datadiscoveryai.service;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.lang.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class GzipRequestResponseInterceptor implements ClientHttpRequestInterceptor {

    private final HttpHeaders defaultHeaders;

    public GzipRequestResponseInterceptor(String apiKey, String internalKey) {
        this.defaultHeaders = new HttpHeaders();
        defaultHeaders.add(HttpHeaders.CONTENT_TYPE, "application/json");
        defaultHeaders.add(HttpHeaders.ACCEPT, "application/json");
        defaultHeaders.add(HttpHeaders.ACCEPT_ENCODING, "gzip"); // Request GZIP-compressed responses
        defaultHeaders.add("X-API-Key", apiKey);
        defaultHeaders.add("X-INTERNAL-AI-HEADER-SECRET", internalKey);
    }

    @NonNull
    @Override
    public ClientHttpResponse intercept(HttpRequest request, @NonNull byte[] body, ClientHttpRequestExecution execution) throws IOException {
        HttpHeaders headers = request.getHeaders();
        headers.addAll(defaultHeaders);

        // Only compress if body is not empty
        byte[] compressedBody = body;
        if (body.length > 0) {
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            try (GZIPOutputStream gzipStream = new GZIPOutputStream(byteStream)) {
                gzipStream.write(body);
            }
            compressedBody = byteStream.toByteArray();
            headers.add(HttpHeaders.CONTENT_ENCODING, "gzip");
        }

        ClientHttpResponse response = execution.execute(request, compressedBody);

        // Decompress response if GZIP-encoded
        if ("gzip".equalsIgnoreCase(response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING))) {
            return new GzipClientHttpResponseWrapper(response);
        }
        return response;
    }

    // Wrapper to decompress GZIP response
    private record GzipClientHttpResponseWrapper(ClientHttpResponse delegate) implements ClientHttpResponse {

        @NonNull
        @Override
        public InputStream getBody() throws IOException {
            return new GZIPInputStream(delegate.getBody());
        }

        @NonNull
        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @NonNull
        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @NonNull
        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
