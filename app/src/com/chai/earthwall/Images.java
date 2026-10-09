package com.chai.earthwall;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Decoding helpers; run these off the GL thread. */
final class Images {
    private Images() {}

    static final class Single {
        final ByteBuffer data; final int w, h;
        Single(ByteBuffer d, int w, int h) { this.data = d; this.w = w; this.h = h; }
    }

    static Bitmap decodeRgb(InputStream in) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        o.inScaled = false;
        Bitmap b = BitmapFactory.decodeStream(in, null, o);
        in.close();
        if (b == null) throw new IOException("decode failed");
        return b;
    }

    /** Decodes a greyscale image into a tightly packed one-byte-per-pixel buffer. */
    static Single decodeSingle(InputStream in) throws IOException {
        Bitmap b = decodeRgb(in);
        int w = b.getWidth(), h = b.getHeight();
        ByteBuffer out = ByteBuffer.allocateDirect(w * h).order(ByteOrder.nativeOrder());
        int[] row = new int[w];
        byte[] rowOut = new byte[w];
        for (int y = 0; y < h; y++) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) rowOut[x] = (byte) ((row[x] >> 16) & 0xFF);
            out.put(rowOut);
        }
        b.recycle();
        out.position(0);
        return new Single(out, w, h);
    }
}
