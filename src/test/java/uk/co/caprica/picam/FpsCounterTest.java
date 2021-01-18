package uk.co.caprica.picam;

import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.caprica.picam.utils.FpsCounter;

public class FpsCounterTest {

    Logger logger = LoggerFactory.getLogger(FpsCounterTest.class);

    @Test
    public void shouldCountFps() throws Exception {

        FpsCounter counter = new FpsCounter();
        int i = 0;
        while (i < 100) {
            i++;
            Thread.sleep(Math.round(40 + (20 * Math.random())));
            float fps = counter.update();
            if (i % 10 == 0) {
                System.out.println(String.format("FPS: %f", fps));
            }
            Assert.assertTrue(fps > 15 && fps < 25);
        }
    }

}
