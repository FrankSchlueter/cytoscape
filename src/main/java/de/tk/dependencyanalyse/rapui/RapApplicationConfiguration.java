package de.tk.dependencyanalyse.rapui;

import de.tk.dependencyanalyse.rapui.visgraph.examples.CsvExampleEntryPoint;
import de.tk.dependencyanalyse.rapui.visgraph.examples.NvlIconEntryPoint;

import org.eclipse.rap.rwt.application.Application;
import org.eclipse.rap.rwt.application.ApplicationConfiguration;

/**
 * RAP application configuration. Registers the {@code /graph} entry point
 * backed by {@link CsvExampleEntryPoint} (Cytoscape + fcose example) and
 * the {@code /nvl-icon} entry point backed by {@link NvlIconEntryPoint}
 * (NVL overlay-icon demo).
 *
 * <p>Activated via the {@code org.eclipse.rap.applicationConfiguration} init
 * parameter on the {@link org.eclipse.rap.rwt.engine.RWTServlet}.</p>
 */
public class RapApplicationConfiguration implements ApplicationConfiguration {

    @Override
    public void configure(Application application) {
        application.addEntryPoint("/graph", CsvExampleEntryPoint.class, null);
        application.addEntryPoint("/nvl-icon", NvlIconEntryPoint.class, null);
        application.setOperationMode(Application.OperationMode.SWT_COMPATIBILITY);
    }
}
