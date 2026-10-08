package io.github.crossben.accordsync.spring;

import io.github.crossben.accordsync.server.AccordServer;
import io.github.crossben.accordsync.server.HttpRequest;
import io.github.crossben.accordsync.server.HttpResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates servlet requests to {@link AccordServer#handle} and back, byte for byte: the raw body
 * (read up to one byte past the body limit, so the server answers 413 itself), every header, the
 * raw query string. Spring MVC never sees these requests (no message converters, no error page,
 * no MVC CORS handling: CORS comes from the definition).
 */
public class AccordServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    private final transient AccordServer server;
    private final String prefix;

    /**
     * A servlet.
     *
     * @param server the server
     * @param prefix the path prefix ("" or "/x") stripped before routing
     */
    public AccordServlet(AccordServer server, String prefix) {
        this.server = server;
        this.prefix = prefix;
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getRequestURI();
        String context = req.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) path = path.substring(context.length());
        if (path.startsWith(prefix)) path = path.substring(prefix.length());
        Map<String, String> headers = new LinkedHashMap<>();
        for (Enumeration<String> names = req.getHeaderNames(); names.hasMoreElements(); ) {
            String name = names.nextElement();
            String value = req.getHeader(name);
            if (value != null) headers.putIfAbsent(name, value);
        }
        byte[] body;
        try (InputStream in = req.getInputStream()) {
            body = in.readNBytes(server.definition().limits().maxBodyBytes() + 1);
        }
        HttpResponse res = server.handle(new HttpRequest(req.getMethod(), path, req.getQueryString(), headers, body));
        resp.setStatus(res.status());
        for (Map.Entry<String, String> h : res.headers()) resp.addHeader(h.getKey(), h.getValue());
        boolean empty = res.status() == 204 || res.status() == 304 || "HEAD".equalsIgnoreCase(req.getMethod());
        if (!empty) {
            resp.setContentLength(res.body().length);
            try (OutputStream out = resp.getOutputStream()) {
                out.write(res.body());
            }
        }
    }
}
