package de.tk.dependencyanalyse.rapui.visgraph;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRegistration;

import org.eclipse.rap.rwt.engine.RWTServlet;
import org.eclipse.rap.rwt.engine.RWTServletContextListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Registers the {@link RWTServlet} at the {@code /graph} URL pattern, the
 * {@link RWTServletContextListener} that builds the {@code ApplicationContextImpl},
 * and a Spring resource handler that serves the static assets under
 * {@code /cytoscape/}, {@code /vis-network/}, {@code /vis-graph/}, and
 * {@code /cytoscape-viewer.html}.
 *
 * <p><b>Registration order matters:</b> the {@link RWTServletContextListener}
 * must be added to the context <i>before</i> the {@link RWTServlet} is
 * registered, otherwise the listener's {@code contextInitialized} runs while
 * the servlet map is still empty and throws
 * {@code UnsupportedOperationException} from Jetty 12's EE10
 * {@code ServletContext.getServletRegistration} stub.</p>
 *
 * <p>The RAP servlet registration is gated on {@code rap.enabled} so the
 * REST + static-asset layer can run on containers that are not
 * RAP-compatible (e.g. Jetty 11). When RAP is disabled, the endpoint
 * {@code /graph} returns 404; the REST controller and all static assets
 * remain reachable.</p>
 */
@Configuration
public class RapServletInitializer implements WebMvcConfigurer {

    @Value("${rap.enabled:true}")
    private boolean rapEnabled;

    @Bean
    public ServletContextInitializer servletContextInitializer() {
        return (ServletContext servletContext) -> {
            if (!rapEnabled) return;

            // Init parameters must be set BEFORE adding the context listener.
            servletContext.setInitParameter(
                    "org.eclipse.rap.applicationConfiguration",
                    RapApplicationConfiguration.class.getName());

            // IMPORTANT: listener first, then servlet. Reverse order causes
            // Jetty 12 to throw UnsupportedOperationException from
            // RWTServletContextListener.doReadEntryPointRunnerConfiguration.
            servletContext.addListener(new RWTServletContextListener());

            ServletRegistration.Dynamic rap = servletContext.addServlet("rap", RWTServlet.class);
            rap.setLoadOnStartup(1);
            rap.addMapping(new String[] { "/graph" });
        };
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String rwtResourcesPath = System.getProperty("org.eclipse.rap.rwt.resourceLocation");
        if (rwtResourcesPath == null) {
            rwtResourcesPath = Application.getResourceLocation();
        }
        Path resolved = Paths.get(rwtResourcesPath).toAbsolutePath();
        File dir = resolved.toFile();
        if (dir.exists()) {
            registry.addResourceHandler("/rwt-resources/**")
                    .addResourceLocations("file:" + resolved + "/");
        }
        registry.addResourceHandler("/cytoscape/**")
                .addResourceLocations("classpath:/static/cytoscape/");
        registry.addResourceHandler("/vis-network/**")
                .addResourceLocations("classpath:/static/vis-network/");
        registry.addResourceHandler("/vis-graph/**")
                .addResourceLocations("classpath:/static/vis-graph/");
        registry.addResourceHandler("/cytoscape-viewer.html")
                .addResourceLocations("classpath:/static/cytoscape-viewer.html");
    }
}
