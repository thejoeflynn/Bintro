package com.bintro.media;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.image.BufferedImage;

public class ThumbnailExtractor {

    /**
     * Grabs the video frame at approximately 10% into the clip and returns it
     * as a JavaFX-displayable {@link Image}.
     *
     * @return the thumbnail, or {@code null} if no frame could be decoded
     */
    public Image extractThumbnail(Clip clip) throws FrameGrabber.Exception {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(clip.file());
             Java2DFrameConverter converter = new Java2DFrameConverter()) {

            grabber.start();
            long lengthMicros = grabber.getLengthInTime();
            if (lengthMicros > 0) {
                grabber.setTimestamp(lengthMicros / 10);
            }

            Frame frame = grabber.grabImage();
            if (frame == null) {
                return null;
            }
            BufferedImage bi = converter.convert(frame);
            if (bi == null) {
                return null;
            }
            return SwingFXUtils.toFXImage(bi, null);
        }
    }
}
