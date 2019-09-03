package org.zalando.logbook.servlet;

import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import org.zalando.fauxpas.ThrowingUnaryOperator;
import org.zalando.logbook.Headers;
import org.zalando.logbook.HttpRequest;
import org.zalando.logbook.Origin;

import javax.activation.MimeType;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.list;
import static java.util.stream.Collectors.joining;
import static org.zalando.logbook.servlet.ByteStreams.toByteArray;

final class RemoteRequest extends HttpServletRequestWrapper implements HttpRequest {

    private final FormRequestMode formRequestMode = FormRequestMode.fromProperties();
    private final AtomicReference<State> state = new AtomicReference<>(new Unbuffered());

    RemoteRequest(final HttpServletRequest request) {
        super(request);
    }

    @Override
    public String getProtocolVersion() {
        return getProtocol();
    }

    @Override
    public Origin getOrigin() {
        return Origin.REMOTE;
    }

    @Override
    public String getRemote() {
        return getRemoteAddr();
    }

    @Override
    public String getHost() {
        return getServerName();
    }

    @Override
    public Optional<Integer> getPort() {
        return Optional.of(getServerPort());
    }

    @Override
    public String getPath() {
        return getRequestURI();
    }

    @Override
    public String getQuery() {
        return Optional.ofNullable(getQueryString()).orElse("");
    }

    @Override
    public Map<String, List<String>> getHeaders() {
        final Map<String, List<String>> headers = Headers.empty();
        final Enumeration<String> names = getHeaderNames();

        while (names.hasMoreElements()) {
            final String name = names.nextElement();

            headers.put(name, list(getHeaders(name)));
        }

        // TODO immutable?
        return headers;
    }

    @Override
    public Charset getCharset() {
        return Optional.ofNullable(getCharacterEncoding()).map(Charset::forName).orElse(UTF_8);
    }

    @Override
    public HttpRequest withBody() {
        transitionTo(State::withBody);
        return this;
    }

    @Override
    public HttpRequest withoutBody() {
        transitionTo(State::withoutBody);
        return this;
    }

    @SneakyThrows
    static String encode(final String s, final String charset) {
        return URLEncoder.encode(s, charset);
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        return transitionTo(State::expose).getInputStream();
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return transitionTo(State::expose).getReader();
    }

    @Override
    public byte[] getBody() {
        return transitionTo(State::expose).getBody();
    }

    private byte[] buffer() throws IOException {
        if (isFormRequest()) {
            switch (formRequestMode) {
                case PARAMETER:
                    return reconstructBodyFromParameters();
                case OFF:
                    return new byte[0];
                default:
                    break;
            }
        }

        return toByteArray(super.getInputStream());
    }

    private boolean isFormRequest() {
        return Optional.ofNullable(getContentType())
                .flatMap(MimeTypes::parse)
                .filter(this::isFormRequest)
                .isPresent();
    }

    private boolean isFormRequest(final MimeType contentType) {
        return "application".equals(contentType.getPrimaryType()) &&
                "x-www-form-urlencoded".equals(contentType.getSubType());
    }

    private byte[] reconstructBodyFromParameters() {
        return getParameterMap().entrySet().stream()
                .flatMap(entry -> Arrays.stream(entry.getValue())
                        .map(value -> encode(entry.getKey()) + "=" + encode(value)))
                .collect(joining("&"))
                .getBytes(UTF_8);
    }

    private static String encode(final String s) {
        return encode(s, "UTF-8");
    }

    private State transitionTo(final ThrowingUnaryOperator<State, IOException> transition) {
        return state.updateAndGet(transition);
    }

    private abstract class State {

        byte[] EMPTY = new byte[0];

        State withBody() {
            return this;
        }

        State withoutBody() {
            return this;
        }

        State expose() throws IOException {
            return this;
        }

        ServletInputStream getInputStream() throws IOException {
            return RemoteRequest.super.getInputStream();
        }

        BufferedReader getReader() throws IOException {
            return RemoteRequest.super.getReader();
        }

        byte[] getBody() {
            return EMPTY;
        }

    }

    private final class Unbuffered extends State {

        @Override
        public State withBody() {
            return new Offering();
        }

        @Override
        public State expose() {
            return new Passing();
        }

    }

    private final class Offering extends State {

        @Override
        public State withoutBody() {
            return new Unbuffered();
        }

        @Override
        public State expose() throws IOException {
            return new Buffering();
        }

    }

    private final class Passing extends State {

    }

    @AllArgsConstructor
    private final class Buffering extends State {

        private final byte[] buffer;

        private Buffering() throws IOException {
            this(RemoteRequest.this.buffer());
        }

        @Override
        public State withoutBody() {
            return new Ignoring(buffer);
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ServletInputStreamAdapter(new ByteArrayInputStream(buffer));
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), getCharset()));
        }

        @Override
        public byte[] getBody() {
            return buffer;
        }

    }

    @AllArgsConstructor
    private final class Ignoring extends State {

        private final byte[] buffer;

        @Override
        public State withBody() {
            return new Buffering(buffer);
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ServletInputStreamAdapter(new ByteArrayInputStream(buffer));
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), getCharset()));
        }

    }

}
