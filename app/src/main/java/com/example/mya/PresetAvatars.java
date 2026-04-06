package com.example.mya;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import java.io.ByteArrayOutputStream;

/**
 * Built-in profile pictures (drawables) users can pick as favorites; uploaded like gallery photos.
 */
public final class PresetAvatars {

    private PresetAvatars() {}

    public static final int[] DRAWABLE_IDS = {
            R.drawable.avatar_favorite_1,
            R.drawable.avatar_favorite_2,
            R.drawable.avatar_favorite_3,
            R.drawable.avatar_favorite_4,
            R.drawable.avatar_favorite_5,
    };

    /** Max edge length before JPEG encode (keeps uploads reasonable). */
    private static final int MAX_SIDE = 1024;

    /**
     * Loads a drawable, optionally scales down, encodes as JPEG bytes for Firebase upload.
     */
    public static byte[] encodeDrawableAsJpeg(Context context, int resId) {
        Bitmap bmp = BitmapFactory.decodeResource(context.getResources(), resId);
        if (bmp == null) return null;
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w > MAX_SIDE || h > MAX_SIDE) {
            float scale = Math.min((float) MAX_SIDE / w, (float) MAX_SIDE / h);
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            Bitmap scaled = Bitmap.createBitmap(bmp, 0, 0, w, h, m, true);
            if (scaled != bmp) {
                bmp.recycle();
            }
            bmp = scaled;
        }
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, os);
        bmp.recycle();
        return os.toByteArray();
    }
}
