package com.oai.titanarum;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TableStreamConfidenceTest {
    @Test
    void confidenceSerializesWhenSetAndOmittedWhenNull() throws Exception {
        ObjectMapper om = new ObjectMapper();

        TableExtractor.TableHit lattice = new TableExtractor.TableHit();
        lattice.extractionMethod = "lattice";
        lattice.confidence = null;
        assertFalse(om.writeValueAsString(lattice).contains("confidence"),
            "null confidence must be omitted (NON_NULL)");

        TableExtractor.TableHit stream = new TableExtractor.TableHit();
        stream.extractionMethod = "stream";
        stream.confidence = 0.87;
        assertTrue(om.writeValueAsString(stream).contains("\"confidence\":0.87"));
    }
}
