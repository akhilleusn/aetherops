package com.aetherops.integrations.webhook;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.util.StreamUtils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Reads the request body once into memory and serves it repeatably, so a signature can be
 * verified over the exact raw bytes BEFORE Spring's HttpMessageConverter parses them into a
 * DTO, while that same DTO parsing still works normally afterward.
 *
 * Deliberately NOT Spring's ContentCachingRequestWrapper: that class only tees bytes into
 * its cache as something else reads them via getInputStream() — for a JSON body (unlike
 * Slack's form-encoded body, which Spring parses via getParameterMap() and which
 * ContentCachingRequestWrapper specifically special-cases), draining it in a filter to
 * verify a signature would leave nothing in the underlying stream for Jackson to read
 * afterward, silently breaking the payload. Reading into a byte[] once and having
 * getInputStream()/getReader() hand back a fresh stream over that array every time avoids
 * that: the filter verifies against the same bytes the controller later parses.
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] cachedBody;

    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.cachedBody = StreamUtils.copyToByteArray(request.getInputStream());
    }

    public byte[] getCachedBody() {
        return cachedBody;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(cachedBody);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return byteArrayInputStream.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // no-op: this is a fully-buffered, synchronous stream
            }

            @Override
            public int read() {
                return byteArrayInputStream.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
