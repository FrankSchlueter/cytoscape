package de.tk.dependencyanalyse.rapui.visgraph.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies the Sigma-specific enum flag on {@link LayoutAlgorithm}: the
 * five new values {@code FORCE_ATLAS_SIGMA},
 * {@code FORCE_DIRECTED_2_SIGMA}, {@code NOVERLAP_SIGMA},
 * {@code CIRCULAR_SIGMA}, {@code RANDOM_SIGMA} exist with the correct
 * flags, and {@code valuesForSigma()} returns exactly these five in
 * declaration order.
 */
class LayoutAlgorithmSigmaTest {

    @Test
    void newSigmaValuesHaveCorrectFlags() {
        assertEquals(true, LayoutAlgorithm.FORCE_ATLAS_SIGMA.isSupportedBySigma());
        assertEquals(false, LayoutAlgorithm.FORCE_ATLAS_SIGMA.isSupportedByVisNetwork());
        assertEquals(false, LayoutAlgorithm.FORCE_ATLAS_SIGMA.isSupportedByCytoscape());

        assertEquals(true, LayoutAlgorithm.FORCE_DIRECTED_2_SIGMA.isSupportedBySigma());
        assertEquals(true, LayoutAlgorithm.NOVERLAP_SIGMA.isSupportedBySigma());
        assertEquals(true, LayoutAlgorithm.CIRCULAR_SIGMA.isSupportedBySigma());
        assertEquals(true, LayoutAlgorithm.RANDOM_SIGMA.isSupportedBySigma());

        for (LayoutAlgorithm la : LayoutAlgorithm.values()) {
            // No Sigma layout is also a vis or cytoscape layout — values
            // remain engine-orthogonal.
            assertEquals(false, la.isSupportedByVisNetwork() && la.isSupportedBySigma(),
                    la.name() + " should not be both vis and sigma");
            assertEquals(false, la.isSupportedByCytoscape() && la.isSupportedBySigma(),
                    la.name() + " should not be both cytoscape and sigma");
        }
    }

    @Test
    void valuesForSigmaReturnsExactlyFive() {
        LayoutAlgorithm[] values = LayoutAlgorithm.valuesForSigma();
        assertEquals(5, values.length);
        assertSame(LayoutAlgorithm.FORCE_ATLAS_SIGMA, values[0]);
        assertSame(LayoutAlgorithm.FORCE_DIRECTED_2_SIGMA, values[1]);
        assertSame(LayoutAlgorithm.NOVERLAP_SIGMA, values[2]);
        assertSame(LayoutAlgorithm.CIRCULAR_SIGMA, values[3]);
        assertSame(LayoutAlgorithm.RANDOM_SIGMA, values[4]);
    }

    @Test
    void preExistingValuesKeepTheirFlags() {
        // Regression guard: the constructor signature now takes a third
        // boolean (supportedBySigma). Verify the existing values
        // didn't flip their other flags in the migration.
        assertEquals(true, LayoutAlgorithm.FORCE_ATLAS_2D.isSupportedByVisNetwork());
        assertEquals(false, LayoutAlgorithm.FORCE_ATLAS_2D.isSupportedBySigma());

        assertEquals(true, LayoutAlgorithm.FCOSE.isSupportedByCytoscape());
        assertEquals(false, LayoutAlgorithm.FCOSE.isSupportedBySigma());

        assertEquals(true, LayoutAlgorithm.LEIDEN_GRID.isSupportedByCytoscape());
        assertEquals(false, LayoutAlgorithm.LEIDEN_GRID.isSupportedBySigma());
    }
}
