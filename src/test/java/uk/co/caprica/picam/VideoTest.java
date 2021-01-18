/*
 * This file is part of picam.
 *
 * picam is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * picam is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with picam.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright 2016-2019 Caprica Software Limited.
 */

package uk.co.caprica.picam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.caprica.picam.enums.Encoding;
import uk.co.caprica.picam.handlers.BasicVideoCaptureHandler;
import uk.co.caprica.picam.handlers.PictureCaptureHandler;
import uk.co.caprica.picam.handlers.SequentialFilePictureCaptureHandler;
import uk.co.caprica.picam.handlers.VideoCaptureHandler;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;

import static uk.co.caprica.picam.CameraConfiguration.cameraConfiguration;

/**
 * A simple test to capture one or more images from the camera and save them to disk.
 *
 * 3280 x 2464
 * 2592 x 1944
 * 1640 x 1212
 *  820 x  616
 *  648 x  486
 */
public class VideoTest {

    private final Logger logger = LoggerFactory.getLogger(VideoTest.class);

    public static void main(String[] args) throws Exception {
        new VideoTest(args);
    }

    private VideoTest(String[] args) throws Exception {
        logger.info("VideoTest()");

        if (args.length !=3) {
            System.err.println("Usage: <width> <height> <count>");
            System.exit(1);
        }

        int width = Integer.parseInt(args[0]);
        int height = Integer.parseInt(args[1]);

        int max = Integer.parseInt(args[2]);

        CameraConfiguration config = cameraConfiguration()
                .width(width)
                .height(height)
                .delay(5)
                .encoding(Encoding.JPEG)
                .fps(25)
                .quality(85)
                .useVideoMode(true)
//            .brightness(50)
//            .contrast(-30)
//            .saturation(80)
//            .sharpness(100)
//            .stabiliseVideo()
//            .shutterSpeed(10)
//            .iso(4)
//            .exposureMode(ExposureMode.FIREWORKS)
//            .exposureMeteringMode(ExposureMeteringMode.BACKLIT)
//            .exposureCompensation(5)
//            .dynamicRangeCompressionStrength(DynamicRangeCompressionStrength.MAX)
//            .automaticWhiteBalance(AutomaticWhiteBalanceMode.FLUORESCENT)
//            .imageEffect(ImageEffect.SKETCH)
//            .flipHorizontally()
//            .flipVertically()
//            .rotation(rotation)
//              .crop(0.25f, 0.25f, 0.5f, 0.5f);
                ;

        try (Camera camera = new Camera(config)) {
            logger.info("created camera " + camera);

            AtomicInteger counter = new AtomicInteger(0);

            camera.capture((data, frameNumber) -> {
                int i = counter.incrementAndGet();
                logger.info("Begin " + i);
                File file = new File(String.format("image-%04d.jpg", i));
                try (OutputStream out = new FileOutputStream(file)) {
                    out.write(data);
                    out.flush();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                logger.info(" End " + i);
                return i < max;
            });
        }
        logger.info("finished");
    }
}