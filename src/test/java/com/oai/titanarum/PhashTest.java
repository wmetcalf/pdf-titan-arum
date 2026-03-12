package com.oai.titanarum;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class PhashTest {

    private String callComputePhash(BufferedImage img) throws Exception {
        Method m = PdfTitanArumApp.class.getDeclaredMethod("computePhash", BufferedImage.class);
        m.setAccessible(true);
        return (String) m.invoke(null, img);
    }

    @Test
    void allWhiteImage() throws Exception {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 100; y++)
            for (int x = 0; x < 100; x++)
                img.setRGB(x, y, 0xFFFFFF);
        assertEquals("8000000000000000", callComputePhash(img));
    }

    @Test
    void allBlackImage() throws Exception {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        assertEquals("0000000000000000", callComputePhash(img));
    }

    @Test
    void calibrationImageMatchesPython() throws Exception {
        File f = new File("src/test/resources/calibration.png");
        org.junit.jupiter.api.Assumptions.assumeTrue(f.exists(), "calibration.png not present, skipping");
        BufferedImage img = ImageIO.read(f);
        String expected = "REPLACE_ME";
        assertEquals(expected, callComputePhash(img));
    }
}
