package io.github.ekkzy.softlumi.ui;

import static org.junit.Assert.*;
import org.junit.Test;

public final class UiImageLoaderSamplingTest {
    @Test public void ordinaryWallpaperPreviewKeepsEnoughSourcePixelsForItsVisibleSize(){
        for(int[] size:new int[][]{{369,204},{738,408},{246,136}}){
            int sample=UiImageLoader.wallpaperSampleSize(1672,941,size[0],size[1]);
            assertTrue("The real preview must not be enlarged because sampling discarded its pixels",1672/sample>=size[0]);
            assertTrue("A cover preview needs enough pixels in both directions",941/sample>=size[1]);
        }
    }

    @Test public void extremeImagesRemainInsideTheDecodeRectangle(){
        for(int[] source:new int[][]{{12000,1000},{1000,12000},{100000,1},{1,100000},
                {Integer.MAX_VALUE,Integer.MAX_VALUE},{Integer.MAX_VALUE,1},{1,Integer.MAX_VALUE}}){
            int sample=UiImageLoader.wallpaperSampleSize(source[0],source[1],960,540);
            assertTrue("Decode width must stay bounded even for a panorama",(source[0]+(long)sample-1)/sample<=1920);
            assertTrue("Decode height must stay bounded even for a portrait",(source[1]+(long)sample-1)/sample<=1080);
        }
    }

    @Test public void smallerSourceDoesNotLoseMorePixels(){
        assertEquals(1,UiImageLoader.wallpaperSampleSize(640,360,1920,1080));
    }

    @Test public void untrustedPosterDimensionsRespectActualRoundedDecodeBounds(){
        for(int[] source:new int[][]{{20_000_000,1},{1,20_000_000},{Integer.MAX_VALUE,1},
                {1,Integer.MAX_VALUE},{Integer.MAX_VALUE,Integer.MAX_VALUE}}){
            int sample=UiImageLoader.posterSampleSize(source[0],source[1],960,540);
            long decodedWidth=(source[0]+(long)sample-1)/sample;
            long decodedHeight=(source[1]+(long)sample-1)/sample;
            assertTrue("Panorama width must be bounded before bitmap allocation",decodedWidth<=1920);
            assertTrue("Portrait height must be bounded before bitmap allocation",decodedHeight<=1080);
            assertTrue("A one-pixel axis must not defeat the pixel budget",decodedWidth*decodedHeight<=1920L*1080);
        }
        assertEquals("A normal full-HD poster keeps every requested pixel",2,
                UiImageLoader.posterSampleSize(1920,1080,960,540));
    }
}
