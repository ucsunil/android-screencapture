package com.screens.capture.utils;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.media.Image;
import android.media.ImageReader;
import android.view.Display;
import android.view.Surface;

import com.screens.capture.services.ScreenViewerService;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Created by Sunil on 7/21/2016.
 */
public class ScreenProvider implements ImageReader.OnImageAvailableListener {

    private final int width;
    private final int height;
    private final ImageReader imageReader;
    private ScreenViewerService service;
    private Bitmap latestBitmap = null;

    public ScreenProvider(ScreenViewerService service) {
        this.service = service;

        Display display = service.getWindowManager().getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);

        int width = size.x;
        int height = size.y;

        while(width*height > (2<<19)) {
            width = width >> 1;
            height = height >> 1;
        }
        this.width = width;
        this.height = height;
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this, service.getHandler());
    }

    @Override
    public void onImageAvailable(ImageReader imageReader) {
        final Image image = imageReader.acquireLatestImage();
        if(image != null) {
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride*width;
            int bitmapWidth = width + rowPadding/pixelStride;

            if(latestBitmap == null ||
                    latestBitmap.getWidth() != bitmapWidth ||
                    latestBitmap.getHeight() != height) {
                if(latestBitmap != null) {
                    latestBitmap.recycle();
                }
                latestBitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888);
            }
            latestBitmap.copyPixelsToBuffer(buffer);
            if(image != null) {
                image.close();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Bitmap cropped = Bitmap.createBitmap(latestBitmap, 0, 0, width, height);
            cropped.compress(Bitmap.CompressFormat.PNG, 100, baos);
            byte[] newPic = baos.toByteArray();
            service.updateImage(newPic);
        }
    }

    public Surface getSurface() {
        return imageReader.getSurface();
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return  height;
    }

    public void close() {
        imageReader.close();
    }
}
