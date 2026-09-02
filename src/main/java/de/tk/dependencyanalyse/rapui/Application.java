package de.tk.dependencyanalyse.rapui;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point. Configures the embedded Jetty server on port 8085
 * (via {@code application.yml}). The Eclipse RAP {@code RWTServlet} is
 * registered at {@code /graph} by {@link RapServletInitializer}.
 */
@SpringBootApplication
public class Application {

    public static String getResourceLocation() {
        String tempDir = System.getProperty("java.io.tmpdir");
        String resourceLocation = tempDir + "/rwt-resources";
        return resourceLocation;
    }

    public static void main(String[] args) {
        String resourceLocation = getResourceLocation();
        System.setProperty("org.eclipse.rap.rwt.resourceLocation", resourceLocation);

        SpringApplication.run(Application.class, args);
        System.out.println("Server started at http://localhost:8085/graph");
        System.out.println("Server started at http://localhost:8085/vis-graph");
        System.out.println("Server started at http://localhost:8085/api/sample-graph");
    }
}
