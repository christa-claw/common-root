package org.religioustext.app.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Serves the generated chapter audio at {@code /audio/*}.
 *
 * <p>The TTS job writes mp3s outside the repo and rsyncs them to the server;
 * nothing served them, so every file was on disk and unreachable over HTTP.
 *
 * <p>WHY A SERVLET AND NOT A RESOURCE HANDLER. Vaadin's servlet is mapped to
 * {@code /*}, and in the servlet spec a path mapping beats the dispatcher's
 * default {@code /} mapping — so an ordinary Spring MVC resource handler never
 * sees these requests and the reader gets the SPA's own HTML back with a 200,
 * which an &lt;audio&gt; element simply refuses to play. Registering the handler
 * as a servlet at {@code /audio/*} gives it a more specific mapping than
 * Vaadin's, so it wins.
 *
 * <p>{@link ResourceHttpRequestHandler} is used rather than hand-rolled
 * streaming because it answers HTTP range requests, which is what lets a reader
 * seek inside a chapter instead of waiting out the whole file, and it resolves
 * the media type so mp3s arrive as {@code audio/mpeg}.
 *
 * <p>WHY THE SERVLET SETS A REQUEST ATTRIBUTE. That handler is an MVC citizen:
 * it asks for the path to serve via {@code HandlerMapping
 * .PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE}, which the DispatcherServlet's
 * handler mapping normally sets. Running it straight from a servlet there is no
 * DispatcherServlet, so the attribute is absent and every request dies with
 * {@code IllegalStateException: Required request attribute ... is not set} —
 * which surfaces as a 302 to /login, because the container's error dispatch is
 * itself a secured request. So the servlet sets the attribute from
 * {@code getPathInfo()} (already decoded, and the handler still rejects
 * traversal itself) before delegating.
 *
 * <p>The directory defaults to the folder holding the manifest, so a working
 * {@code commonroot.audio.index} needs no second setting. A missing directory
 * just means 404s — the reader treats that exactly like "no audio generated".
 */
@Configuration
public class AudioStaticConfig {

    private static final Logger LOG = LoggerFactory.getLogger(AudioStaticConfig.class);

    /** Servlet/bean name — kept for readable logs and stack traces. */
    static final String HANDLER_BEAN = "audioResourceHandler";

    private final Path root;

    public AudioStaticConfig(
            @Value("${commonroot.audio.index:/srv/audio/index.json}") final String aManifestPath,
            @Value("${commonroot.audio.dir:}") final String aAudioDir) {
        this.root = (aAudioDir == null || aAudioDir.isBlank())
                ? Paths.get(aManifestPath).toAbsolutePath().getParent()
                : Paths.get(aAudioDir).toAbsolutePath();
        if (root == null || !Files.isDirectory(root)) {
            LOG.warn("audio directory not present at {} — /audio/* will 404", root);
        } else {
            LOG.info("serving /audio/* from {}", root);
        }
    }

    @Bean(name = HANDLER_BEAN)
    public ResourceHttpRequestHandler audioResourceHandler() {
        final ResourceHttpRequestHandler handler = new ResourceHttpRequestHandler();
        if (root != null) {
            // Trailing separator matters: without it the last path segment is
            // treated as a file name prefix rather than a directory.
            handler.setLocations(List.of(new FileSystemResource(root.toString() + "/")));
        }
        handler.setCacheSeconds(60 * 60 * 24 * 30);   // a chapter is never rewritten
        return handler;
    }

    @Bean
    public ServletRegistrationBean<HttpServlet> audioServletRegistration(
            final ResourceHttpRequestHandler aHandler) {
        final HttpServlet servlet = new HttpServlet() {
            @Override
            protected void service(final HttpServletRequest aReq,
                                   final HttpServletResponse aRes)
                    throws IOException {
                final String pathInfo = aReq.getPathInfo();
                aReq.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE,
                                  pathInfo == null ? "" : pathInfo);
                try {
                    aHandler.handleRequest(aReq, aRes);
                } catch (final IOException e) {
                    throw e;
                } catch (final Exception e) {
                    throw new IOException("serving " + pathInfo, e);
                }
            }
        };
        final ServletRegistrationBean<HttpServlet> reg =
                new ServletRegistrationBean<>(servlet, "/audio/*");
        reg.setName(HANDLER_BEAN);
        reg.setLoadOnStartup(1);
        return reg;
    }
}
