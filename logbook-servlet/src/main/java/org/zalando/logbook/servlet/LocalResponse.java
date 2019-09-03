package org.zalando.logbook.servlet;

import lombok.AllArgsConstructor;
import org.zalando.fauxpas.ThrowingUnaryOperator;
import org.zalando.logbook.Headers;
import org.zalando.logbook.HttpResponse;
import org.zalando.logbook.Origin;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;

final class LocalResponse extends HttpServletResponseWrapper implements HttpResponse {

    private final String protocolVersion;

    private final AtomicReference<State> state = new AtomicReference<>(new Unbuffered());

    LocalResponse(final HttpServletResponse response, final String protocolVersion) {
        super(response);
        this.protocolVersion = protocolVersion;
    }

    @Override
    public Origin getOrigin() {
        return Origin.LOCAL;
    }

    @Override
    public String getProtocolVersion() {
        return protocolVersion;
    }

    @Override
    public Map<String, List<String>> getHeaders() {
        final Map<String, List<String>> headers = Headers.empty();

        for (final String header : getHeaderNames()) {
            headers.put(header, new ArrayList<>(getHeaders(header)));
        }

        return headers;
    }

    @Override
    public Charset getCharset() {
        return Optional.ofNullable(getCharacterEncoding()).map(Charset::forName).orElse(UTF_8);
    }

    @Override
    public HttpResponse withBody() {
        transitionTo(State::withBody);
        return this;
    }

    @Override
    public HttpResponse withoutBody() {
        transitionTo(State::withoutBody);
        return this;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        return transitionTo(State::expose).getOutputStream();
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        return transitionTo(State::expose).getWriter();
    }

    @Override
    public byte[] getBody() {
        return state.get().getBody();
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

        ServletOutputStream getOutputStream() throws IOException {
            return LocalResponse.super.getOutputStream();
        }

        PrintWriter getWriter() throws IOException {
            return LocalResponse.super.getWriter();
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

        private final Tee buffer;

        private Buffering() throws IOException {
            this(new Tee(LocalResponse.super.getOutputStream(), LocalResponse.this.getCharset()));
        }

        @Override
        public State withoutBody() {
            return new Ignoring(buffer);
        }

        @Override
        public ServletOutputStream getOutputStream() {
            return buffer.getOutputStream();
        }

        @Override
        public PrintWriter getWriter() {
            return buffer.getWriter();
        }

        @Override
        byte[] getBody() {
            return buffer.getBytes();
        }
    }

    @AllArgsConstructor
    private final class Ignoring extends State {

        private final Tee buffer;

        @Override
        public State withBody() {
            return new Buffering(buffer);
        }

        @Override
        public ServletOutputStream getOutputStream() {
            return buffer.getOutputStream();
        }

        @Override
        public PrintWriter getWriter() {
            return buffer.getWriter();
        }

    }

    private static final class Tee {

        private final ByteArrayOutputStream branch;
        private final TeeServletOutputStream output;
        private final PrintWriter writer;

        private Tee(final ServletOutputStream original, final Charset charset) {
            this.branch = new ByteArrayOutputStream();
            this.output = new TeeServletOutputStream(original, branch);
            this.writer = new PrintWriter(new OutputStreamWriter(output, charset));
        }

        ServletOutputStream getOutputStream() {
            return output;
        }

        PrintWriter getWriter() {
            return writer;
        }

        byte[] getBytes() {
            return branch.toByteArray();
        }

    }

    @AllArgsConstructor
    private static final class TeeServletOutputStream extends ServletOutputStream {

        private final ServletOutputStream original;
        private final OutputStream branch;

        @Override
        public void write(final int b) throws IOException {
            original.write(b);
            branch.write(b);
        }

        @Override
        public void write(final byte[] b, final int off, final int len) throws IOException {
            original.write(b, off, len);
            branch.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            original.flush();
            branch.flush();
        }

        @Override
        public void close() throws IOException {
            original.close();
            branch.close();
        }

        @Override
        public boolean isReady() {
            return original.isReady();
        }

        @Override
        public void setWriteListener(final WriteListener listener) {
            original.setWriteListener(listener);
        }

    }

}
